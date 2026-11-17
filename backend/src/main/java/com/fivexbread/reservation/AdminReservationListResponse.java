package com.fivexbread.reservation;

import java.util.List;

/** 판매일 예약 목록 (A-05). 마지막 페이지면 nextCursor 는 null */
public record AdminReservationListResponse(List<AdminReservationResponse> items, String nextCursor, boolean hasNext) {
}
