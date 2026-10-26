package com.fivexbread.saledate;

/** 422 INVALID_SALE_DATE 의 errors[] 한 항목 */
public record SaleDateViolation(String field, Reason reason, String message) {

    public enum Reason {
        OUT_OF_RANGE,              // 내일~60일 밖
        OPEN_NOT_HALF_HOUR,        // 오픈 시각의 분이 00·30 이 아님
        OPEN_IN_PAST,              // 오픈 시각이 현재 이전
        OPEN_TOO_LATE,             // 오픈 > 마감 − 1시간
        OPEN_AT_LOCKED,            // 오픈 후 오픈 시각 변경
        CLOSE_AT_POSTPONED,        // 오픈 후 마감 늦추기
        CLOSE_TOO_LATE,            // 마감 > 첫 픽업 − 1시간 (Q-03)
        PICKUP_COUNT,              // 시간대 1~12개
        PICKUP_NOT_HALF_HOUR,
        PICKUP_OUT_OF_HOURS,       // 영업시간 밖
        PICKUP_DUPLICATE,
        PICKUP_HAS_RESERVATIONS,   // 예약이 있는 시간대를 빼려 함
        HOLIDAY_WITH_PICKUPS,      // 휴무일인데 시간대가 있음
        HOLIDAY_HAS_RESERVATIONS   // 예약이 있는 날을 휴무일로
    }
}
