package com.fivexbread.saledate;

/**
 * 손님 빵 목록 쿼리 한 행 (13-4 카탈로그 쿼리, DTO 프로젝션). 엔티티를 만들지 않아 영속성 컨텍스트에 올라가지 않는다.
 * JPQL 생성자 표현식이 넘기는 타입 그대로 래퍼 타입으로 받는다.
 * 불변이라 로컬 캐시에 그대로 둔다. 잔여는 자주 바뀌어 넣지 않는다 (Redis).
 */
public record SaleBreadRow(
        Long breadId,
        String name,
        Integer price,
        Integer perUserLimit,
        Integer displayOrder
) {
}
