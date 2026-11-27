package com.fivexbread.domain.reservation.model.enums;

/** 예약 상태를 바꾼 주체. 사장님 id·이름은 내보내지 않는다. SYSTEM 은 노쇼 배치 */
public enum HistoryActorType {
    CUSTOMER,
    ADMIN,
    SYSTEM
}
