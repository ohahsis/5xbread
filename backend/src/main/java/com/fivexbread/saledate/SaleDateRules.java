package com.fivexbread.saledate;

import static com.fivexbread.saledate.SaleDateViolation.Reason.*;

import com.fivexbread.saledate.SaleDateViolation.Reason;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 판매일 설정 규칙 (13-8 1단계, 9장 필드 규격 "판매일·픽업 시간대"). 첫 위반에서 멈추지 않고 모두 모은다.
 * DB 상태가 필요한 규칙(예약이 있는 시간대·날)은 SaleDateService 가 더한다.
 */
final class SaleDateRules {

    static final int MAX_DAYS_AHEAD = 60;
    static final int MAX_PICKUP_SLOTS = 12;

    private final List<SaleDateViolation> violations = new ArrayList<>();

    private SaleDateRules() {
    }

    /**
     * @param existing 이미 있는 판매일이면 그 값(오픈 후 변경 제한에 씀), 새로 만들면 null
     */
    static List<SaleDateViolation> check(LocalDate date, LocalDateTime openAt, LocalDateTime closeAt, boolean holiday,
                                         List<LocalTime> pickupTimes, SaleDate existing, LocalDateTime now,
                                         StoreProperties store) {
        SaleDateRules rules = new SaleDateRules();
        rules.checkDate(date, now.toLocalDate());
        rules.checkWindow(openAt, closeAt, existing, now);
        rules.checkPickups(date, closeAt, holiday, pickupTimes, store);
        return List.copyOf(rules.violations);
    }

    private void checkDate(LocalDate date, LocalDate today) {
        if (date.isBefore(today.plusDays(1)) || date.isAfter(today.plusDays(MAX_DAYS_AHEAD))) {
            add("saleDate", OUT_OF_RANGE, "판매일은 내일부터 60일 이내로 설정해 주세요");
        }
    }

    private void checkWindow(LocalDateTime openAt, LocalDateTime closeAt, SaleDate existing, LocalDateTime now) {
        if (!isHalfHour(openAt.toLocalTime())) {
            add("openAt", OPEN_NOT_HALF_HOUR, "오픈 시각은 00분 또는 30분으로 설정해 주세요");
        }
        boolean opened = existing != null && existing.isOpened(now);
        boolean openChanged = existing == null || !existing.getOpenAt().equals(openAt);
        if (opened && openChanged) {
            add("openAt", OPEN_AT_LOCKED, "예약이 열린 뒤에는 오픈 시각을 바꿀 수 없습니다");
        } else if (openChanged && !openAt.isAfter(now)) {
            add("openAt", OPEN_IN_PAST, "오픈 시각은 현재 이후여야 합니다");
        }
        if (opened && closeAt.isAfter(existing.getCloseAt())) {
            add("closeAt", CLOSE_AT_POSTPONED, "예약이 열린 뒤에는 마감을 늦출 수 없고 앞당기기만 할 수 있습니다");
        }
        if (openAt.isAfter(closeAt.minusHours(1))) {
            add("openAt", OPEN_TOO_LATE, "오픈 시각은 마감 1시간 전까지여야 합니다");
        }
    }

    private void checkPickups(LocalDate date, LocalDateTime closeAt, boolean holiday, List<LocalTime> pickupTimes,
                              StoreProperties store) {
        if (holiday) {
            if (!pickupTimes.isEmpty()) {
                add("pickupTimes", HOLIDAY_WITH_PICKUPS, "휴무일에는 픽업 시간대를 둘 수 없습니다");
            }
            return;
        }
        if (pickupTimes.isEmpty() || pickupTimes.size() > MAX_PICKUP_SLOTS) {
            add("pickupTimes", PICKUP_COUNT, "픽업 시간대는 1~12개로 설정해 주세요");
        }
        Set<LocalTime> seen = new HashSet<>();
        for (LocalTime time : pickupTimes) {
            if (!isHalfHour(time) || time.isBefore(store.openTime()) || !time.isBefore(store.closeTime())) {
                add("pickupTimes", isHalfHour(time) ? PICKUP_OUT_OF_HOURS : PICKUP_NOT_HALF_HOUR,
                        "픽업 시각은 영업시간 안에서 30분 단위로 설정해 주세요 (" + time + ")");
            }
            if (!seen.add(time)) {
                add("pickupTimes", PICKUP_DUPLICATE, "같은 픽업 시각이 두 번 있습니다 (" + time + ")");
            }
        }
        // 30분 단위이고 겹치지 않으면 "서로 30분 이상"도 지켜진다
        pickupTimes.stream().min(LocalTime::compareTo).ifPresent(first -> {
            if (closeAt.isAfter(date.atTime(first).minusHours(1))) {
                add("closeAt", CLOSE_TOO_LATE, "마감 시각은 첫 픽업 1시간 전까지여야 합니다");
            }
        });
    }

    private static boolean isHalfHour(LocalTime time) {
        return time.getMinute() % 30 == 0 && time.getSecond() == 0 && time.getNano() == 0;
    }

    private void add(String field, Reason reason, String message) {
        violations.add(new SaleDateViolation(field, reason, message));
    }
}
