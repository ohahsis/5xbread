package com.fivexbread.domain.reservation.model.vo;

/** ReservationRepository.findItems 한 행 */
public interface ReservationItemRow {

    long getReservationId();

    long getBreadId();

    String getName();

    int getQuantity();

    int getUnitPrice();
}
