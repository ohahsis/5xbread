package com.fivexbread.reservation;

import com.fivexbread.common.BusinessException;
import com.fivexbread.common.ErrorCode;
import com.fivexbread.member.Member;
import com.fivexbread.member.MemberRepository;
import com.fivexbread.member.MemberStatus;
import com.fivexbread.saledate.DailyStockRepository;
import com.fivexbread.saledate.PickupSlot;
import com.fivexbread.saledate.PickupSlotRepository;
import com.fivexbread.saledate.SaleBreadRow;
import com.fivexbread.saledate.SaleDate;
import com.fivexbread.saledate.SaleDateRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 예약 업무 규칙 (13-1 4단계, decisions.md 6장 "C-04 업무 규칙 오류"). 수량·한도·하루 건수는 Redis Lua 가 원자적으로 본다.
 * 순서: 판매일 없음 404 → 휴무일·예약 기간 밖 422 → 픽업 시간대 422 → 팔지 않는 빵 422 → 1건 20개 422 → 노쇼 제한 422.
 * 판매일 캐시(30초)는 3단계에서 붙인다. 그 전까지는 DB 를 읽는다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReservationRuleService {

    private final SaleDateRepository saleDateRepository;
    private final PickupSlotRepository pickupSlotRepository;
    private final DailyStockRepository dailyStockRepository;
    private final MemberRepository memberRepository;
    private final ReservationProperties properties;
    private final Clock clock;

    ReservationPlan check(long memberId, ReservationCreateRequest request) {
        // 기간 검증: 판매일 존재(404), 오픈 전·마감·휴무일
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDate date = request.saleDate();
        SaleDate saleDate = saleDateRepository.findById(date)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        switch (saleDate.status(now)) {
            case NOT_OPEN -> throw new BusinessException(ErrorCode.RESERVATION_NOT_OPEN);
            case CLOSED, HOLIDAY -> throw new BusinessException(ErrorCode.RESERVATION_CLOSED);
            case OPEN -> {
            }
        }
        // 소속 검증: 시간대와 빵이 그 판매일에 속하는지
        // 본문의 시간대·빵은 없는 id 여도 404 가 아니라 업무 규칙 위반 422 (URL 자원이 아님)
        PickupSlot slot = pickupSlotRepository.findById(request.pickupSlotId())
                .filter(found -> found.getSaleDate().equals(date))
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_PICKUP_SLOT));

        Map<Long, SaleBreadRow> breads = dailyStockRepository.findSaleBreads(date).stream() // 그 날 팔 수 있는 빵 목록을 DB 에서 읽어 breadId -> 빵 정보 Map 으로 만든다.
                .collect(Collectors.toMap(SaleBreadRow::breadId, Function.identity()));
        List<Map<String, Long>> unavailable = request.items().stream()  // 손님이 요청한 빵 중에서 그날 목록에 없는 빵만 전부 모은다.(장바구니 화면에 표시 가능)
                .filter(item -> !breads.containsKey(item.breadId()))
                .map(item -> Map.of("breadId", item.breadId()))
                .toList();
        if (!unavailable.isEmpty()) {
            throw new BusinessException(ErrorCode.BREAD_NOT_AVAILABLE, null, Map.of("errors", unavailable));
        }

        // 한도 검증: 1건 20개만 여기서 본다. 1인 한도·하루 3건은 재고 차감과 함께 Lua 에서 원자적으로 본다
        long totalQuantity = request.items().stream().mapToLong(ReservationCreateRequest.Item::quantity).sum();
        if (totalQuantity > properties.maxTotalQuantity()) {
            throw new BusinessException(ErrorCode.TOTAL_QUANTITY_EXCEEDED,
                    "예약 1건에 최대 " + properties.maxTotalQuantity() + "개까지 담을 수 있습니다");
        }

        // 상태 검증: 회원이 활성 상태인지, 노쇼 제한 기간인지
        Member member = memberRepository.findById(memberId)
                .filter(found -> found.getStatus() == MemberStatus.ACTIVE)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHENTICATED));
        LocalDate blockedUntil = member.getReservationBlockedUntil();
        if (blockedUntil != null && !now.toLocalDate().isAfter(blockedUntil)) {       // R-17: 그날까지 제한
            throw new BusinessException(ErrorCode.RESERVATION_BLOCKED, null, Map.of("blockedUntil", blockedUntil));
        }

        List<ReservationPlan.Item> items = request.items().stream()
                .sorted(Comparator.comparing(ReservationCreateRequest.Item::breadId))   // breadId 로 정렬. 데드락의 순환 대기 없앰
                .map(item -> {
                    SaleBreadRow bread = breads.get(item.breadId());
                    return new ReservationPlan.Item(item.breadId(), bread.name(), bread.price(), item.quantity(),
                            bread.perUserLimit());
                })
                .toList();
        String phone = member.getPhone();
        return new ReservationPlan(memberId, phone.substring(phone.length() - 4), date, slot.getId(),
                slot.getPickupAt(), items);
    }
}
