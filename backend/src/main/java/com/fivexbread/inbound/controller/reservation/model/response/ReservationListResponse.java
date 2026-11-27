package com.fivexbread.inbound.controller.reservation.model.response;

import com.fivexbread.domain.reservation.model.vo.ReservationPage;
import com.fivexbread.inbound.controller.reservation.model.response.ReservationDetailResponse;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** 커서 목록 (10장). 마지막 페이지면 nextCursor 는 null */
public record ReservationListResponse(List<ReservationDetailResponse> items, @Schema(nullable = true) String nextCursor, boolean hasNext) {

    public static ReservationListResponse from(ReservationPage page) {
        return new ReservationListResponse(page.items().stream().map(ReservationDetailResponse::from).toList(),
                page.nextCursor(), page.hasNext());
    }
}
