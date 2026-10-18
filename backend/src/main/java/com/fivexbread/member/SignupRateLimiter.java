package com.fivexbread.member;

import com.fivexbread.common.BusinessException;
import com.fivexbread.common.ErrorCode;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.support.atomic.RedisAtomicLong;
import org.springframework.stereotype.Component;

/**
 * IP당 하루 가입 횟수 제한 (AU-02). 앱 3개가 같은 값을 보도록 Redis 카운터를 쓴다.
 * 키: signup:ip:{ip}:{yyyyMMdd}, TTL: 그날 KST 자정까지
 */
@Component
@RequiredArgsConstructor
public class SignupRateLimiter {

    private static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE;

    private final RedisConnectionFactory connectionFactory;
    private final SignupProperties properties;
    private final Clock clock;

    /** 이번 시도를 센다. 한도를 넘으면 429 + Retry-After(자정까지 남은 초) */
    public void acquire(String clientIp) {
        ZonedDateTime now = ZonedDateTime.now(clock);
        LocalDate today = now.toLocalDate();
        ZonedDateTime midnight = today.plusDays(1).atStartOfDay(clock.getZone());

        // 생성자는 키가 없으면 0으로 만든다(GET 후 SET). 증가는 INCR 이라 원자적
        RedisAtomicLong counter = new RedisAtomicLong(key(clientIp, today), connectionFactory);
        long count = counter.incrementAndGet();
        // INCR과 EXPIREAT은 따로 실행되므로 첫 번째만이 아니라 매번 건다.
        // 생성자의 SET이 TTL을 지우는 경우와 두 명령 사이에 앱이 죽는 경우에도 TTL 없는 키가 남지 않게
        counter.expireAt(midnight.toInstant());

        if (count > properties.dailyLimitPerIp()) {
            long retryAfter = Math.max(1, Duration.between(now, midnight).toSeconds());
            throw BusinessException.withRetryAfter(ErrorCode.TOO_MANY_REQUESTS, retryAfter);
        }
    }

    static String key(String clientIp, LocalDate day) {
        return "signup:ip:" + clientIp + ":" + day.format(DAY);
    }
}
