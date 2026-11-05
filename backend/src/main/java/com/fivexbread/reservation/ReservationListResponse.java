package com.fivexbread.reservation;

import java.util.List;

/** 커서 목록 (10장). 마지막 페이지면 nextCursor 는 null */
public record ReservationListResponse(List<ReservationDetailResponse> items, String nextCursor, boolean hasNext) {
}
