package com.fivexbread.reservation;

/**
 * 1인 한도 검사 (R-03·R-04). db-only(v1)에서 DB 합계로 판단할 때 쓴다. Redis 모드의 RESERVE Lua 와 같은 규칙·같은 errors[] 형식이다:
 * 이미 예약한 수량 + 이번 수량이 한도를 넘으면 PER_USER_LIMIT_EXCEEDED, remaining 은 남은 한도(0 이상).
 */
final class ReservationLimits {

    private ReservationLimits() {
    }

    /** 이 빵에서 1인 한도를 넘으면 실패 항목, 아니면 null. @param already 이 날 이 손님이 이 빵을 이미 예약한 수량(취소 제외) */
    static StockFailure perUserFailure(ReservationPlan.Item item, int already) {
        if (item.perUserLimit() == null || already + item.quantity() <= item.perUserLimit()) {
            return null;
        }
        return new StockFailure(item.breadId(), "PER_USER_LIMIT_EXCEEDED", Math.max(item.perUserLimit() - already, 0));
    }

    static boolean dailyCountExceeded(long activeCount, int dailyLimit) {
        return activeCount >= dailyLimit;
    }
}
