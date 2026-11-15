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

    /**
     * 재고 키를 DB 에서 적재한 시각(epoch 밀리초, String). 재고 키와 함께 만들고 함께 지운다.
     * 아웃박스 이벤트는 이 시각이 이벤트 생성 시각보다 늦지 않을 때만 INCRBY 한다. 더 늦게 적재된 키에는 이미 DB 의 변경이 들어 있다
     * (decisions.md 6장 "아웃박스 지연 처리와 재적재"). 키가 없으면 이 기능 전에 적재된 키로 보고 적용한다.
     */
    public static String loadedAt(LocalDate saleDate, long breadId) {
        return "stock_loaded_at:{" + saleDate + "}:" + breadId;
    }

    /** 아웃박스 이벤트를 Redis 에 적용했다는 표시 (멱등 소비). 판매일 해시태그라 재고 키와 같은 Lua 에서 다룬다 */
    public static String applied(LocalDate saleDate, long eventId) {
        return "applied:{" + saleDate + "}:" + eventId;
    }

    /**
     * 정합성 점검(N-05)이 처음 관측한 차이. 값은 "차이:관측 시각(epoch 밀리초)". 같은 차이가 다음 회차에도 보이면 보정한다.
     * 앱 3개 중 락을 잡은 앱이 매번 달라질 수 있어 메모리가 아니라 Redis 에 둔다 (decisions.md 6장 "N-05 연속 2회 관측").
     */
    public static String reconcileSeen(LocalDate saleDate, long breadId) {
        return "reconcile_seen:{" + saleDate + "}:" + breadId;
    }

    /**
     * 판매일 잔여 버전 (C-03, 13-3 3단계). 잔여를 바꾸는 Lua 가 같은 스크립트에서 INCR 하고 바뀐 잔여와 함께 발행한다
     * (StockRedisSync.LUA_PUBLISH_STOCK). 손님 목록 응답의 version 도 이 값이라, 화면은 목록보다 큰 버전의 메시지만 적용한다.
     * 키가 없으면 0. 만료는 처음 만들 때 바뀐 재고 키와 같게(판매일 다음 날 0시).
     */
    public static String version(LocalDate saleDate) {
        return "stockver:{" + saleDate + "}";
    }

    /** 잔여 변경 Pub/Sub 채널 (C-03). 앱마다 STOCK_CHANNEL_PATTERN 으로 모든 판매일을 구독한다. Lua(LUA_PUBLISH_STOCK)에도 같은 문자열 */
    public static final String STOCK_CHANNEL_PREFIX = "channel:stock:";
    public static final String STOCK_CHANNEL_PATTERN = STOCK_CHANNEL_PREFIX + "*";

    /** Lua 에서 비교할 epoch 밀리초 (KST LocalDateTime → 시각) */
    public static long epochMillis(LocalDateTime time) {
        return time.atZone(KST).toInstant().toEpochMilli();
    }

    public static Duration ttl(LocalDate saleDate, LocalDateTime now) {
        Duration ttl = Duration.between(now.atZone(KST), saleDate.plusDays(1).atStartOfDay(KST));
        return ttl.isNegative() || ttl.isZero() ? Duration.ofMinutes(1) : ttl;
    }
}
