package com.fivexbread.saledate;

/** 빵별 수량 합계 (네이티브 쿼리 인터페이스 프로젝션) */
public interface BreadQuantity {

    Long getBreadId();

    Long getQuantity();
}
