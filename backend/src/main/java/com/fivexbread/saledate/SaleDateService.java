package com.fivexbread.saledate;

import static com.fivexbread.common.ClockConfig.KST;

import com.fivexbread.common.BusinessException;
import com.fivexbread.common.ErrorCode;
import com.fivexbread.saledate.SaleDateViolation.Reason;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 판매일 설정 (A-04, 13-8 1단계). 수량 설정 버전(sale_date.version)은 올리지 않는다 (decisions.md 6장).
 * 픽업 시간대는 통째로 지우고 다시 만들지 않고 빠진 것만 지우고 새것만 더한다. 남는 시간대의 id 를 예약이 가리키기 때문.
 */
@Service
@RequiredArgsConstructor
public class SaleDateService {

    private final SaleDateRepository saleDateRepository;
    private final PickupSlotRepository pickupSlotRepository;
    private final DailyStockRepository dailyStockRepository;
    private final StoreProperties storeProperties;
    private final Clock clock;

    public record Result(SaleDateResponse saleDate, boolean created) {
    }

    @Transactional
    public Result put(LocalDate date, SaleDateRequest request) {
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime openAt = toKst(request.openAt());
        LocalDateTime closeAt = toKst(request.closeAt());
        boolean holiday = request.holiday();
        Optional<SaleDate> existing = saleDateRepository.findById(date);
        List<PickupSlot> currentSlots = existing.isPresent()
                ? pickupSlotRepository.findBySaleDateOrderByPickupAt(date) : List.of();

        List<SaleDateViolation> violations = new ArrayList<>(SaleDateRules.check(date, openAt, closeAt, holiday,
                request.pickupTimes(), existing.orElse(null), now, storeProperties));

        Set<LocalDateTime> wanted = new HashSet<>();
        request.pickupTimes().forEach(time -> wanted.add(date.atTime(time)));
        List<PickupSlot> removed = currentSlots.stream().filter(slot -> !wanted.contains(slot.getPickupAt())).toList();
        if (holiday && existing.isPresent() && pickupSlotRepository.existsReservationOn(date) == 1) {
            violations.add(new SaleDateViolation("holiday", Reason.HOLIDAY_HAS_RESERVATIONS,
                    "예약이 있는 날은 휴무일로 바꿀 수 없습니다"));
        } else if (!removed.isEmpty()) {
            List<Long> reserved = pickupSlotRepository.findSlotIdsWithReservations(
                    removed.stream().map(PickupSlot::getId).toList());
            removed.stream().filter(slot -> reserved.contains(slot.getId())).forEach(slot ->
                    violations.add(new SaleDateViolation("pickupTimes", Reason.PICKUP_HAS_RESERVATIONS,
                            "예약이 있는 픽업 시간대는 뺄 수 없습니다 (" + slot.getPickupAt().toLocalTime() + ")")));
        }
        if (!violations.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_SALE_DATE, null, Map.of("errors", violations));
        }

        SaleDate saleDate = existing.orElse(null);
        if (saleDate == null) {
            saleDate = saleDateRepository.saveAndFlush(SaleDate.create(date, openAt, closeAt, holiday));
        } else {
            saleDate.replace(openAt, closeAt, holiday);
        }
        pickupSlotRepository.deleteAllInBatch(removed);
        Set<LocalDateTime> kept = new HashSet<>();
        currentSlots.stream().filter(slot -> wanted.contains(slot.getPickupAt()))
                .forEach(slot -> kept.add(slot.getPickupAt()));
        pickupSlotRepository.saveAll(wanted.stream().filter(time -> !kept.contains(time))
                .map(time -> new PickupSlot(date, time)).toList());
        if (holiday) {
            dailyStockRepository.deleteBySaleDate(date);    // 휴무일에는 수량을 두지 않는다. 예약이 없음을 위에서 확인했다
        }
        return new Result(SaleDateResponse.of(saleDate, pickupSlotRepository.findBySaleDateOrderByPickupAt(date)),
                existing.isEmpty());
    }

    private static LocalDateTime toKst(OffsetDateTime time) {
        return time.atZoneSameInstant(KST).toLocalDateTime();
    }
}
