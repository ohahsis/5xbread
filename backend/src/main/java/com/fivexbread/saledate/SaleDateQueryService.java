package com.fivexbread.saledate;

import com.fivexbread.common.BusinessException;
import com.fivexbread.common.ErrorCode;
import com.fivexbread.saledate.SaleBreadsResponse.SaleBreadResponse;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 손님용 판매일·빵 목록·픽업 시간대 조회 (C-02, 13-4). 1차는 Caffeine·Redis 없이 DB 만 읽는다 (decisions.md 4장).
 * 상태는 주입한 Clock 의 현재 시각으로 계산한다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SaleDateQueryService {

    // 매진은 아래로, 그 안에서는 노출 순서 (13-4 6단계)
    private static final Comparator<SaleBreadRow> LIST_ORDER = Comparator
            .comparing((SaleBreadRow row) -> row.remaining() <= 0)
            .thenComparing(SaleBreadRow::displayOrder)
            .thenComparing(SaleBreadRow::breadId);

    private final SaleDateRepository saleDateRepository;
    private final PickupSlotRepository pickupSlotRepository;
    private final DailyStockRepository dailyStockRepository;
    private final Clock clock;

    public List<SaleDateStatusResponse> list(LocalDate from, LocalDate to) {
        LocalDateTime now = LocalDateTime.now(clock);
        return saleDateRepository.findBySaleDateBetweenOrderBySaleDate(from, to).stream()
                .map(saleDate -> SaleDateStatusResponse.of(saleDate, now))
                .toList();
    }

    public SaleDateStatusResponse get(LocalDate date) {
        return SaleDateStatusResponse.of(findSaleDate(date), LocalDateTime.now(clock));
    }

    /** memberId 가 null 이면 비로그인: 내 예약 수량을 조회하지 않고 null 로 둔다 */
    public SaleBreadsResponse breads(LocalDate date, Long memberId) {
        SaleDateStatus status = findSaleDate(date).status(LocalDateTime.now(clock));
        Map<Long, Long> mine = memberId == null ? Map.of()
                : dailyStockRepository.sumReservedQuantities(memberId, date).stream()
                        .collect(Collectors.toMap(BreadQuantity::getBreadId, BreadQuantity::getQuantity));
        List<SaleBreadResponse> breads = dailyStockRepository.findSaleBreads(date).stream()
                .sorted(LIST_ORDER)
                .map(row -> new SaleBreadResponse(row.breadId(), row.name(), row.price(), row.remaining(),
                        row.perUserLimit(),
                        memberId == null ? null : mine.getOrDefault(row.breadId(), 0L).intValue(),
                        SaleBreadStatus.of(status, row.remaining())))
                .toList();
        return new SaleBreadsResponse(date, status, breads);
    }

    public List<PickupSlotResponse> pickupSlots(LocalDate date) {
        findSaleDate(date);
        return pickupSlotRepository.findBySaleDateOrderByPickupAt(date).stream()
                .map(PickupSlotResponse::of)
                .toList();
    }

    private SaleDate findSaleDate(LocalDate date) {
        return saleDateRepository.findById(date).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }
}
