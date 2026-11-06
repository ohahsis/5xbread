package com.fivexbread.saledate;

import com.fivexbread.common.BusinessException;
import com.fivexbread.common.ErrorCode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 손님용 판매일·픽업 시간대 조회와 빵 목록의 DB 기준 값 (C-02, 13-4). 트랜잭션 안이라 Redis 는 부르지 않는다.
 * 빵 목록 자체는 SaleBreadsFacade 가 만든다. 상태는 주입한 Clock 의 현재 시각으로 계산한다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SaleDateQueryService {

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

    /** Redis 에 없는 빵의 DB 기준 잔여 (예약 가능 총량 − 예약 수량) */
    public Map<Long, Integer> remaining(LocalDate date, List<Long> breadIds) {
        return dailyStockRepository.findRemaining(date, breadIds).stream()
                .collect(Collectors.toMap(BreadQuantity::getBreadId, row -> row.getQuantity().intValue()));
    }

    /** 이 날 손님이 빵별로 예약한 수량(취소 제외). Redis user_qty 가 없을 때의 기준 */
    public Map<Long, Integer> myQuantities(LocalDate date, long memberId) {
        return dailyStockRepository.sumReservedQuantities(memberId, date).stream()
                .collect(Collectors.toMap(BreadQuantity::getBreadId, row -> row.getQuantity().intValue()));
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
