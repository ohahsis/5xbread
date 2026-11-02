package com.fivexbread.reservation;

import com.fivexbread.common.BusinessException;
import com.fivexbread.common.ErrorCode;
import java.time.Clock;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * 회원당 초당 예약 요청 제한 (13-1 2단계, N-23). 키: rl:resv:{memberId}:{epoch 초}, TTL 2초.
 * 앱 3개가 같은 카운터를 보도록 Redis 에 두고, INCR 과 EXPIRE 를 Lua 한 번에 해 TTL 없는 키가 남지 않게 한다.
 */
@Component
@RequiredArgsConstructor
public class ReservationRateLimiter {

    private static final RedisScript<Long> HIT = RedisScript.of("""
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then
              redis.call('EXPIRE', KEYS[1], 2)
            end
            return count
            """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final ReservationProperties properties;
    private final Clock clock;

    /** 한도를 넘으면 429 + Retry-After 1초 */
    public void acquire(long memberId) {
        long second = clock.instant().getEpochSecond();
        Long count = redisTemplate.execute(HIT, List.of("rl:resv:" + memberId + ":" + second));
        if (count != null && count > properties.rateLimitPerSecond()) {
            throw BusinessException.withRetryAfter(ErrorCode.TOO_MANY_REQUESTS, 1);
        }
    }
}
