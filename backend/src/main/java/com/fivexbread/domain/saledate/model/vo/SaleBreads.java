package com.fivexbread.domain.saledate.model.vo;

import com.fivexbread.domain.saledate.model.enums.SaleBreadStatus;
import com.fivexbread.domain.saledate.model.enums.SaleDateStatus;
import java.time.LocalDate;
import java.util.List;

/**
 * 손님 빵 목록 (C-02, 13-4)
 *
 * @param version 실시간 잔여 스냅샷 버전 (C-03, stockver). Redis 장애로 DB 값을 쓰면 0
 */
public record SaleBreads(LocalDate saleDate, SaleDateStatus status, long version, List<Bread> breads) {

    /**
     * @param remaining     잔여. Redis 값이고, 키가 없거나 Redis 장애면 DB 의 예약 가능 총량 − 예약 수량
     * @param perUserLimit  null 이면 한도 없음 (R-04)
     * @param myReservedQty 이 날 내가 예약한 수량(취소 제외). 로그인하지 않았으면 null
     */
    public record Bread(long breadId, String name, int price, int remaining, Integer perUserLimit,
                        Integer myReservedQty, SaleBreadStatus status) {
    }
}
