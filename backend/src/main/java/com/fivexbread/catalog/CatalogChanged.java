package com.fivexbread.catalog;

import java.time.LocalDate;

/**
 * 손님 빵 목록에 보이는 값(빵·판매일·수량)이 바뀌었다는 이벤트. 트랜잭션 안에서 발행하고, 커밋된 뒤에만 캐시를 비운다.
 *
 * @param saleDate 바뀐 판매일. null 이면 모든 판매일
 */
public record CatalogChanged(LocalDate saleDate) {

    public static CatalogChanged of(LocalDate saleDate) {
        return new CatalogChanged(saleDate);
    }

    public static CatalogChanged allDates() {
        return new CatalogChanged(null);
    }
}
