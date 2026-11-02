package com.fivexbread.saledate;

import static com.fivexbread.common.ClockConfig.KST;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 예약 수량 Redis 키 (9장 Redis 키 표, 13-1 5단계). 판매일을 해시태그 {...}로 감싸 Redis Cluster 에서도 한 판매일의 키가
 * 같은 슬롯에 모인다. 여러 키를 다루는 Lua 스크립트가 CROSSSLOT 오류 없이 돈다.
 * 키는 판매일 다음 날 0시에 만료된다. 그 뒤에는 예약·취소·수량 변경이 없다.
 */
public final class StockKeys {

    private StockKeys() {
    }

    /** 잔여 수량 (String 정수) */
    public static String stock(LocalDate saleDate, long breadId) {
        return "stock:{" + saleDate + "}:" + breadId;
    }

    /**
     * 손님별 누적 예약 수량 (Hash: breadId → 수량, count → 그날 예약 건수). count 필드가 있어야 적재한 것으로 본다.
     * 하루 3건도 이 해시에서 수량·한도와 함께 Lua 로 검사한다 (decisions.md 6장 "C-04 하루 3건").
     */
    public static String userQty(LocalDate saleDate, long memberId) {
        return "user_qty:{" + saleDate + "}:" + memberId;
    }

    public static Duration ttl(LocalDate saleDate, LocalDateTime now) {
        Duration ttl = Duration.between(now.atZone(KST), saleDate.plusDays(1).atStartOfDay(KST));
        return ttl.isNegative() || ttl.isZero() ? Duration.ofMinutes(1) : ttl;
    }
}
