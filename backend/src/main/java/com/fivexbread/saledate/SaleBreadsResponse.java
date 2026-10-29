package com.fivexbread.saledate;

import java.time.LocalDate;
import java.util.List;

/**
 * 손님 빵 목록 (C-02, 13-4). 13-4 응답의 version(실시간 잔여 스냅샷 버전)과 imageUrl 은 C-03·사진 업로드 전이라 넣지 않는다.
 */
public record SaleBreadsResponse(
        LocalDate saleDate,
        SaleDateStatus status,
        List<SaleBreadResponse> breads
) {

    /**
     * @param remaining     예약 가능 총량 − 예약 수량 (DB 기준, decisions.md 4장)
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
