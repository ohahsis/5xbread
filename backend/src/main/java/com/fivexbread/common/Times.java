package com.fivexbread.common;

import java.time.ZoneId;

/** 서비스의 시간대. DB DATETIME·엔티티 LocalDateTime·API OffsetDateTime(+09:00) 모두 이 기준이다 (decisions.md 3장) */
public final class Times {

    public static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private Times() {
    }
}
