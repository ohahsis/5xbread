package com.fivexbread.reservation;

/** ReservationRepository.findItems 한 행 */
public interface ReservationItemRow {

    long getReservationId();

    long getBreadId();

    String getName();

    int getQuantity();

    int getUnitPrice();
}
