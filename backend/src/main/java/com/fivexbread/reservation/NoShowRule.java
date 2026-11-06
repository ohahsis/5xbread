package com.fivexbread.reservation;

import java.time.LocalDate;

/**
 * 노쇼 제재 (R-17, Q-04). 최근 30일 안 2회면 그날부터 14일 새 예약 불가. 경계는 decisions.md 6장 "R-17 경계".
 * 정의서 예시: 10/1, 10/8 노쇼 → 10/22까지 제한. 횟수는 노쇼한 판매일 수라 같은 날 예약 여러 건은 1회다.
 */
public final class NoShowRule {

    public static final int BLOCK_THRESHOLD = 2;
    private static final int WINDOW_DAYS = 30;
    private static final int BLOCK_DAYS = 14;

    private NoShowRule() {
    }

    /** 세는 기간의 첫날. 30일 전 판매일까지 세고 31일 전은 세지 않는다 */
    public static LocalDate windowStart(LocalDate today) {
        return today.minusDays(WINDOW_DAYS);
    }

    /** 제한 마지막 날(그날 포함). 21:00 에 정해지므로 오늘은 사실상 끝났고, 내일부터 14일간 막힌다 */
    public static LocalDate blockedUntil(LocalDate today) {
        return today.plusDays(BLOCK_DAYS);
    }
}
