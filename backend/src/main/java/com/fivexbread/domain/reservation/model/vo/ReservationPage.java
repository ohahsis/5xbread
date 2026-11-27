package com.fivexbread.domain.reservation.model.vo;

import java.util.List;

/** 내 예약 커서 목록 (C-05). 마지막 페이지면 nextCursor 는 null */
public record ReservationPage(List<ReservationDetail> items, String nextCursor, boolean hasNext) {
}
