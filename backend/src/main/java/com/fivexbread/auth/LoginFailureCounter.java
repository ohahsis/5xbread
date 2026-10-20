package com.fivexbread.auth;

import com.fivexbread.common.BusinessException;
import com.fivexbread.common.ErrorCode;
import com.fivexbread.common.MaskedPhone;
import java.util.List;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * 로그인 실패 횟수·잠금 (AU-05, decisions.md 6장). 앱 3개가 같은 값을 보도록 Redis 에 둔다.
 * - login:fail:{phone}: 연속 시도 횟수. 로그인 성공 때만 지운다. TTL 은 정리용(24시간)이라 시도할 때마다 갱신한다
 * - login:lock:{phone}: 잠금(10분). 연장하지 않는다. 잠긴 동안의 시도는 세지 않고 비밀번호도 비교하지 않는다
 *
 * 시도는 비밀번호 비교 "전에" 센다. 비교 뒤에 세면 동시에 들어온 요청이 모두 비교까지 가서 5회 제한을 넘겨 대입할 수 있다.
 * 횟수가 5에 닿는 시도는 비교를 시작하는 순간 잠금 키를 먼저 만든다. 그래서 동시에 몇 건이 와도 비교까지 가는 건 최대 5건이고,
 * 잠금이 풀린 뒤(횟수는 5 이상 그대로)에는 1건만 비교하며 틀리면 그대로 10분 잠긴다.
 * 그 시도가 맞으면 성공 처리에서 잠금 키와 횟수를 함께 지운다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LoginFailureCounter {

    /*
     * 반환 {상태, 값}: 잠김 {0, 잠금 남은 ms} / 비교 허용 {1, 횟수} / 비교 허용이지만 이 시도로 잠금을 만듦 {2, 횟수}
     * 잠금 확인·INCR·EXPIRE·잠금 생성을 한 번에 해야 동시에 들어온 요청이 모두 확인을 통과하지 못한다.
     */
    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> ACQUIRE = RedisScript.of("""
            -- KEYS[1]=login:fail:{phone}, KEYS[2]=login:lock:{phone}
            -- ARGV[1]=최대 횟수(5), ARGV[2]=카운터 정리용 TTL(초), ARGV[3]=잠금 시간(초)
            local lockTtl = redis.call('PTTL', KEYS[2])
            
            -- 잠겨 있으면 세지 않고 남은 잠금 시간(ms)을 돌려준다
            if lockTtl > 0 then
                return {0, lockTtl}
            end
            
            -- 비교 전에 센다. TTL은 시도할 때마다 다시 늘린다
            local count = redis.call('INCR', KEYS[1])
            redis.call('EXPIRE', KEYS[1], ARGV[2])
            
            -- 한도에 닿는 시도는 비교를 시작하기 전에 잠금을 먼저 만든다
            if count >= tonumber(ARGV[1]) then
                redis.call('SET', KEYS[2], '1', 'EX', ARGV[3])
                return {2, count}
            end
            return {1, count}
            """, List.class);

    private final StringRedisTemplate redisTemplate;
    private final LoginProperties properties;

    /**
     * 비밀번호 비교 전에 이번 시도를 센다.
     *
     * @return 이 시도가 잠금을 만든 마지막 시도인지. 틀리면 401 대신 423
     * @throws BusinessException 잠겨 있으면 423 + Retry-After(잠금 키의 남은 TTL). 이때는 세지 않는다
     */
    public boolean acquire(String phone) {
        List<?> result = redisTemplate.execute(ACQUIRE, keys(phone),
                String.valueOf(properties.maxFailures()),
                String.valueOf(properties.failureRetention().toSeconds()),
                String.valueOf(properties.lockDuration().toSeconds()));
        long state = ((Number) result.get(0)).longValue();
        if (state == 0) {
            throw locked(((Number) result.get(1)).longValue());
        }
        return state == 2;
    }

    /** 틀렸을 때. 횟수는 acquire 때 이미 셌다. 이 시도가 잠금을 만들었으면 423 */
    public void recordFailure(String phone, boolean lockedByThisAttempt) {
        if (!lockedByThisAttempt) {
            return;
        }
        log.warn("로그인 실패 누적으로 잠금 phone={}", MaskedPhone.from(phone));
        Long lockTtlMillis = redisTemplate.getExpire(lockKey(phone), TimeUnit.MILLISECONDS);
        throw locked(lockTtlMillis == null ? 0 : lockTtlMillis);
    }

    /**
     * 맞았을 때: 횟수와 잠금 키를 지운다.
     * 잠금 키가 있다면 비교 중이던 한도 안의 시도(이 시도 또는 동시에 들어온 앞 순번)가 만든 것이라 함께 지운다.
     * 잠긴 뒤 들어온 시도는 acquire 에서 막혀 여기까지 오지 않는다.
     */
    public void recordSuccess(String phone) {
        redisTemplate.delete(keys(phone));
    }

    // PTTL(ms)을 초 단위로 올림. TTL 은 반올림이라 끝에 0이 나옴
    private static BusinessException locked(long lockTtlMillis) {
        return BusinessException.withRetryAfter(ErrorCode.ACCOUNT_LOCKED, Math.max(1, (lockTtlMillis + 999) / 1000));
    }

    private static List<String> keys(String phone) {
        return List.of(failKey(phone), lockKey(phone));
    }

    static String failKey(String phone) {
        return "login:fail:" + phone;
    }

    static String lockKey(String phone) {
        return "login:lock:" + phone;
    }
}
