package com.fivexbread.reservation;

/**
 * 예약 상태를 바꾸는 사람. canceled_by·이력 actor_id 에 남는다.
 *
 * @param admin 사장님(A-08 직권 취소, A-06 수령). 손님만 취소 마감(R-13)을 적용받는다
 */
public record ReservationActor(long memberId, boolean admin) {

    public static ReservationActor customer(long memberId) {
        return new ReservationActor(memberId, false);
    }

    public static ReservationActor admin(long memberId) {
        return new ReservationActor(memberId, true);
    }
}
