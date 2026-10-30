package com.fivexbread.reservation;

/**
 * 409 응답 errors[]의 한 항목 (10장 실패 응답 예시).
 *
 * @param reason    OUT_OF_STOCK 또는 PER_USER_LIMIT_EXCEEDED
 * @param remaining 이 사유로 더 예약할 수 있는 수량. 재고 부족이면 잔여, 1인 한도 초과면 남은 한도
 */
public record StockFailure(long breadId, String reason, int remaining) {
}
