package com.fivexbread.saledate;

import java.time.LocalDate;
import java.util.List;

/**
 * 손님 빵 목록 (C-02, 13-4). imageUrl 은 사진 업로드 전이라 넣지 않는다.
 *
 * @param version 실시간 잔여 스냅샷 버전 (C-03, stockver). 잔여와 같은 순간에 읽은 값이다. 화면은 /topic/sale-dates/{date}/stock 을
 *                먼저 구독하고 이 목록을 받은 뒤, 이 값보다 큰 version 의 메시지만 적용한다. Redis 장애로 DB 값을 응답하면 0
 */
public record SaleBreadsResponse(
        LocalDate saleDate,
        SaleDateStatus status,
        long version,
        List<SaleBreadResponse> breads
) {

    /**
     * @param remaining     잔여. Redis 값이고, 키가 없거나 Redis 장애면 DB 의 예약 가능 총량 − 예약 수량
     * @param perUserLimit  null 이면 한도 없음 (R-04)
     * @param myReservedQty 이 날 내가 예약한 수량(취소 제외). 로그인하지 않았으면 null
     */
    public record SaleBreadResponse(
            long breadId,
            String name,
            int price,
            int remaining,
            Integer perUserLimit,
            Integer myReservedQty,
            SaleBreadStatus status
    ) {
    }
}
