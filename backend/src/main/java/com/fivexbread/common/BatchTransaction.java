package com.fivexbread.common;

/**
 * 배치(@Scheduled) 트랜잭션의 시간 제한. 요청 처리용 기본값(spring.transaction.default-timeout 8초)은 손님 요청이
 * 오래 붙잡히지 않게 짧게 잡은 값이라, 묶음 단위로 여러 쿼리를 도는 배치에는 따로 둔다 (decisions.md 6장 "DB 시간 제한").
 * 쿼리 하나는 여전히 JDBC socketTimeout(10초) 안에 끝나야 한다.
 */
public final class BatchTransaction {

    public static final int TIMEOUT_SECONDS = 30;

    private BatchTransaction() {
    }
}
