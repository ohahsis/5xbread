package com.fivexbread.saledate;

/**
 * 손님에게 보여 주는 판매일 상태 (10장 C-02, R-11). 예약 기간은 [오픈, 마감): 오픈 시각부터 예약할 수 있고 마감 시각부터는 닫힌다
 * (decisions.md 6장 "R-11 예약 기간 경계").
 */
public enum SaleDateStatus {
    NOT_OPEN,
    OPEN,
    CLOSED,
    HOLIDAY
}
