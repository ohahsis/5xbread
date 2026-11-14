package com.fivexbread.admin;

/** admin_action_log.action. 직권 취소는 필요해지면 더한다 */
public enum AdminAction {
    TEMPORARY_PASSWORD_ISSUED,
    RESERVATION_BLOCK_CLEARED,
    STOCK_RECONCILED                // A-12 수량 재계산. detail 에 빵별 결과
}
