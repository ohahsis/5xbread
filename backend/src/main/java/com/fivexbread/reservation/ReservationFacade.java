package com.fivexbread.reservation;

import com.fivexbread.common.BusinessException;
import com.fivexbread.common.ErrorCode;
import com.fivexbread.reservation.ReservationIdempotency.StoredResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

/**
 * 예약 생성 순서 (13-1). 트랜잭션은 ReservationTxService 에만 있고, Redis 는 그 앞뒤에서만 부른다.
 *
 * 1. 요청 제한 429 → 2. Idempotency SET NX (처리 중 409, 지문 불일치 422, 성공한 요청이면 저장된 201)
 * 3. 업무 규칙 404·422 → 4. Redis Lua 검사·차감 (하루 건수 422, 잔여·1인 한도 409)
 * 5. 예약번호 → 6. DB 트랜잭션 → 7. 커밋 후 처리(afterCommit)
 * 6이 실패하면 4를 보상한다. 다만 원인을 모르는 실패(시간 초과·연결 끊김 등)는 커밋됐을 수 있어, 같은 키의 예약이
 * DB 에 있으면 보상하지 않고 그 예약을 201 로 돌려준다 (decisions.md 6장 "예약 DB 실패 뒤 보상 판단").
 * 3 이후 어디서든 실패하면 Idempotency 키를 지워 같은 키로 다시 시도할 수 있게 한다.
 *
 * mode=db-only(v1, 비교 측정용)는 4와 보상을 건너뛰고 6에서 재고·한도 검사를 DB 로 한다(ReservationTxService.createDbOnly).
 * 1·2·5(요청 제한·Idempotency·예약번호)는 두 모드가 같아 v1 도 Redis 를 쓴다 (decisions.md 6장 "예약 측정 모드").
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReservationFacade {

    private final ReservationRateLimiter rateLimiter;
    private final ReservationIdempotency idempotency;
    private final ReservationRuleService ruleService;
    private final ReservationStockStore stockStore;
    private final ReservationNumbers numbers;
    private final ReservationTxService txService;
    private final ReservationQueryService queryService;
    private final ReservationProperties properties;

    /** 201 응답. body 는 처음이면 ReservationResponse, 같은 키 재요청이면 저장된 JSON */
    public record Created(String location, Object body) {
    }

    public Created create(long memberId, String idempotencyKey, ReservationCreateRequest request) {
        rateLimiter.acquire(memberId);
        String fingerprint = idempotency.fingerprint(memberId, request);
        Optional<StoredResponse> stored = idempotency.begin(idempotencyKey, fingerprint);
        if (stored.isPresent()) {
            return new Created(stored.get().location(), stored.get().body());
        }

        try {
            ReservationPlan plan = ruleService.check(memberId, request);
            ReservationResponse response = properties.mode() == ReservationMode.DB_ONLY
                    ? saveWithDbCheck(plan, idempotencyKey)
                    : saveOrCompensate(plan, stockStore.reserve(plan), idempotencyKey);
            String location = "/api/v1/reservations/" + response.reservationId();
            afterCommit(idempotencyKey, fingerprint, location, response);
            return new Created(location, response);
        } catch (RuntimeException e) {
            idempotency.release(idempotencyKey, fingerprint);
            throw e;
        }
    }

    private ReservationResponse saveOrCompensate(ReservationPlan plan, String generation, String idempotencyKey) {
        try {
            return txService.create(plan, numbers.next(plan.saleDate()), idempotencyKey);
        } catch (StockShortageException e) {
            log.error("Redis 와 DB 잔여가 어긋남: Redis 차감은 통과했지만 DB 조건부 UPDATE 0행 saleDate={} breadIds={}",
                    plan.saleDate(), e.breadIds());
            compensate(plan, generation);
            throw ReservationStockStore.outOfStock(e.breadIds().stream()
                    .map(breadId -> new StockFailure(breadId, "OUT_OF_STOCK", 0))
                    .toList());
        } catch (DataIntegrityViolationException e) {
            compensate(plan, generation);
            throw idempotencyConflict(e);
        } catch (BusinessException e) {
            // 트랜잭션 안의 업무 판단(탈퇴 401, DB 한도 재검사 422·409). 롤백이 확실해 커밋 여부를 볼 필요가 없다
            compensate(plan, generation);
            throw e;
        } catch (RuntimeException e) {
            Optional<ReservationResponse> committed = findCommitted(plan, idempotencyKey);
            if (committed.isPresent()) {
                log.warn("예약 DB 호출이 실패했지만 커밋돼 있어 보상하지 않음 reservationId={} saleDate={} memberId={}",
                        committed.get().reservationId(), plan.saleDate(), plan.memberId(), e);
                return committed.get();
            }
            compensate(plan, generation);
            throw e;
        }
    }

    /**
     * 커밋 응답을 받기 전에 끊긴 경우(시간 초과 등) 실제로는 커밋됐을 수 있다. 그대로 보상하면 Redis 잔여·user_qty 가
     * DB 보다 느슨해져 다음 예약들이 Redis 를 통과한 뒤 DB 에서 막힌다. 그래서 같은 키의 예약을 DB 에서 다시 읽는다.
     * 조회도 실패하면(DB 장애) 커밋되지 않은 것으로 보고 보상한다. 틀려도 Redis 가 느슨해질 뿐, 재고는 조건부 UPDATE 가,
     * 하루 건수·1인 한도는 트랜잭션 안의 DB 재검사가 막는다.
     */
    private Optional<ReservationResponse> findCommitted(ReservationPlan plan, String idempotencyKey) {
        try {
            return queryService.findCreated(plan.memberId(), idempotencyKey);
        } catch (RuntimeException e) {
            log.error("예약 커밋 여부 확인 실패, 커밋되지 않은 것으로 보고 보상 saleDate={} memberId={}",
                    plan.saleDate(), plan.memberId(), e);
            return Optional.empty();
        }
    }

    /** db-only(v1). 실패하면 트랜잭션이 롤백될 뿐 되돌릴 Redis 차감이 없다 */
    private ReservationResponse saveWithDbCheck(ReservationPlan plan, String idempotencyKey) {
        try {
            return txService.createDbOnly(plan, numbers.next(plan.saleDate()), idempotencyKey);
        } catch (DataIntegrityViolationException e) {
            throw idempotencyConflict(e);
        }
    }

    /**
     * 보상도 실패하면 Redis 잔여가 DB 보다 적은 "덜 팔리는" 방향으로 남는다. 3단계 N-05 가 바로잡는다.
     * 아웃박스 대상이 아니다: DB 트랜잭션이 롤백돼 outbox_event 를 함께 커밋할 수 없고(기록할 사실이 없음),
     * 되돌릴 대상은 이 요청이 방금 Redis 에서 뺀 수량이라 같은 요청 안에서 바로 되돌린다.
     */
    private void compensate(ReservationPlan plan, String generation) {
        Map<Long, Integer> quantities = new LinkedHashMap<>();
        plan.items().forEach(item -> quantities.put(item.breadId(), item.quantity()));
        try {
            stockStore.release(plan.saleDate(), plan.memberId(), quantities, generation);
        } catch (RuntimeException e) {
            log.error("예약 Redis 보상 실패 saleDate={} memberId={} items={}", plan.saleDate(), plan.memberId(),
                    summary(plan.items()), e);
        }
    }

    /**
     * 커밋 후 처리를 모으는 곳. 잔여 변경 발행(C-03)은 여기가 아니라 차감 Lua 안에서 한다.
     * 예약은 이미 저장됐으므로 여기서 실패해도 201 을 돌려준다. 같은 키 재요청은 처리 중 표시가 만료될 때까지(기본 2분) 409,
     * 그 뒤에는 DB UNIQUE 에 걸려 Redis 차감을 되돌리고 422 IDEMPOTENCY_KEY_MISMATCH 다(예약은 1건 그대로).
     */
    private void afterCommit(String idempotencyKey, String fingerprint, String location, ReservationResponse response) {
        try {
            idempotency.complete(idempotencyKey, fingerprint, location, response);
        } catch (RuntimeException e) {
            log.error("Idempotency 응답 저장 실패 reservationId={}", response.reservationId(), e);
        }
    }

    // Redis 의 idem 키가 만료·유실된 뒤 같은 키가 다시 온 경우. DB UNIQUE 가 최종 방어
    private static RuntimeException idempotencyConflict(DataIntegrityViolationException e) {
        if (e.getMessage() != null && e.getMessage().contains("uk_reservation_idem")) {
            return new BusinessException(ErrorCode.IDEMPOTENCY_KEY_MISMATCH);
        }
        return e;
    }

    // 로그용: breadId×수량만 남긴다 (개인정보 없음)
    private static List<String> summary(List<ReservationPlan.Item> items) {
        return items.stream().map(item -> item.breadId() + "x" + item.quantity()).toList();
    }
}
