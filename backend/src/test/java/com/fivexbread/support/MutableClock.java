package com.fivexbread.support;

import com.fivexbread.common.ClockConfig;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 테스트 중에 시각을 옮길 수 있는 Clock. 고정하지 않았으면 실제 시각을 돌려준다.
 * 경계 시각(오픈 직전·오픈·마감 직전·마감)을 API 로 확인할 때 쓴다.
 */
public class MutableClock extends Clock {

    private final Clock system = Clock.system(ClockConfig.KST);
    private volatile Instant fixed;

    public void set(LocalDateTime now) {
        this.fixed = now.atZone(ClockConfig.KST).toInstant();
    }

    public void reset() {
        this.fixed = null;
    }

    @Override
    public ZoneId getZone() {
        return ClockConfig.KST;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        throw new UnsupportedOperationException("KST 만 사용한다");
    }

    @Override
    public Instant instant() {
        Instant now = fixed;
        return now != null ? now : system.instant();
    }
}
