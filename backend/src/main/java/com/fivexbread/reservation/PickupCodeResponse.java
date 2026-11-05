package com.fivexbread.reservation;

import java.time.OffsetDateTime;

/** 픽업 QR 토큰 (C-07). 화면은 code 를 QR 로 그린다. expiresAt 부터 무효 (판매일 다음 날 00:00, +09:00) */
public record PickupCodeResponse(String code, OffsetDateTime expiresAt) {
}
