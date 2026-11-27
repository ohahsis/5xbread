package com.fivexbread.domain.reservation.model.vo;

/** 예약 항목 한 줄 + 빵 이름. @param unitPrice 예약 당시 가격 (reservation_item.unit_price) */
public record ReservationLine(long breadId, String name, int quantity, int unitPrice) {
}
