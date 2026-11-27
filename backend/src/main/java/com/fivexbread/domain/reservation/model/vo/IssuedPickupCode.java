package com.fivexbread.domain.reservation.model.vo;

import java.time.Instant;

/** 발급한 픽업 QR 토큰 (C-07). expiresAt 부터 무효 (판매일 다음 날 00:00 KST) */
public record IssuedPickupCode(String code, Instant expiresAt) {
}
