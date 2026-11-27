package com.fivexbread.domain.reservation.model.vo;

import java.time.LocalDate;
import java.util.List;

/**
 * 예약 생성 입력 (C-04). Idempotency 지문이 이 값의 JSON 으로 만들어진다(ReservationIdempotency.fingerprint).
 * 필드 이름·순서·타입을 요청(ReservationCreateRequest)과 똑같이 둔다. 바꾸면 같은 요청의 지문이 달라져 배포 중 재시도가 422 가 된다
 */
public record ReservationCommand(LocalDate saleDate, Long pickupSlotId, List<Item> items) {

    public record Item(Long breadId, int quantity) {
    }
}
