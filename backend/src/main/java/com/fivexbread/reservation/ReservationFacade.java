package com.fivexbread.reservation;

import com.fivexbread.common.BusinessException;
import com.fivexbread.common.ErrorCode;
import com.fivexbread.reservation.ReservationIdempotency.StoredResponse;
import java.util.List;
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
 * 6이 실패하면 4를 보상한다. 3 이후 어디서든 실패하면 Idempotency 키를 지워 같은 키로 다시 시도할 수 있게 한다.
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
            stockStore.reserve(plan);
            ReservationResponse response = saveOrCompensate(plan, idempotencyKey);
            String location = "/api/v1/reservations/" + response.reservationId();
            afterCommit(idempotencyKey, fingerprint, location, response);
            return new Created(location, response);
        } catch (RuntimeException e) {
            idempotency.release(idempotencyKey, fingerprint);
            throw e;
        }
    }

    private ReservationResponse saveOrCompensate(ReservationPlan plan, String idempotencyKey) {
        try {
            return txService.create(plan, numbers.next(plan.saleDate()), idempotencyKey);
        } catch (StockShortageException e) {
            log.error("Redis 와 DB 잔여가 어긋남: Redis 차감은 통과했지만 DB 조건부 UPDATE 0행 saleDate={} breadIds={}",
                    plan.saleDate(), e.breadIds());
            compensate(plan);
            throw ReservationStockStore.outOfStock(e.breadIds().stream()
                    .map(breadId -> new StockFailure(breadId, "OUT_OF_STOCK", 0))
                    .toList());
        } catch (DataIntegrityViolationException e) {
            compensate(plan);
            // Redis 의 idem 키가 만료·유실된 뒤 같은 키가 다시 온 경우. DB UNIQUE 가 최종 방어
            if (e.getMessage() != null && e.getMessage().contains("uk_reservation_idem")) {
                throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_MISMATCH);
            }
            throw e;
        } catch (RuntimeException e) {
            compensate(plan);
            throw e;
        }
    }

    /** 보상도 실패하면 Redis 잔여가 DB 보다 적은 "덜 팔리는" 방향으로 남는다. 3단계 N-05 가 바로잡는다 */
    private void compensate(ReservationPlan plan) {
        try {
            stockStore.release(plan);
        } catch (RuntimeException e) {
            log.error("예약 Redis 보상 실패 saleDate={} memberId={} items={}", plan.saleDate(), plan.memberId(),
                    summary(plan.items()), e);
        }
    }

    /**
     * 커밋 후 처리를 모으는 곳. 3단계에서 잔여 변경 발행(C-03 Pub/Sub)을 여기에 붙인다.
     * 예약은 이미 저장됐으므로 여기서 실패해도 201 을 돌려준다. 같은 키 재요청은 처리 중(409)으로 보이다가 24시간 뒤 풀린다.
     */
    private void afterCommit(String idempotencyKey, String fingerprint, String location, ReservationResponse response) {
        try {
            idempotency.complete(idempotencyKey, fingerprint, location, response);
        } catch (RuntimeException e) {
            log.error("Idempotency 응답 저장 실패 reservationId={}", response.reservationId(), e);
        }
    }

    // 로그용: breadId×수량만 남긴다 (개인정보 없음)
    private static List<String> summary(List<ReservationPlan.Item> items) {
        return items.stream().map(item -> item.breadId() + "x" + item.quantity()).toList();
    }
}
