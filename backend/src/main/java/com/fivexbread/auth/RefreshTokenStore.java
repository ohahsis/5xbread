package com.fivexbread.auth;

import com.fivexbread.common.BusinessException;
import com.fivexbread.common.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * Refresh Token 저장·회전 (정의서 5장, 13-6). 앱 3개가 같은 값을 보도록 Redis 에 둔다.
 * 쿠키 값은 {memberId}.{secret}. secret 은 무작위 32바이트(Base64URL 43자)이고 Redis 에는 그 SHA-256(tokenId)만 둔다.
 * - refresh:{memberId}:{tokenId}: 유효한 토큰. TTL 14일
 * - refresh:index:{memberId}: 그 회원의 tokenId 목록. 한 번에 폐기할 때 쓴다. 발급할 때마다 TTL 갱신
 * - refresh:used:{memberId}:{tokenId}: 이미 교체된 토큰. 원래 남은 TTL 만큼 둔다 (재사용 감지, decisions.md 6장)
 *
 * memberId 는 서명되지 않아 아무나 바꿔 보낼 수 있다. 그래서 "키가 없다"만으로는 회원 토큰을 폐기하지 않고,
 * 실제로 발급됐다가 교체된 기록(used 키)이 있을 때만 재사용으로 보고 전부 폐기한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RefreshTokenStore {

    private static final SecureRandom RANDOM = new SecureRandom();     // 스레드 안전. 호출마다 만들지 않는다
    private static final Base64.Encoder BASE64 = Base64.getUrlEncoder().withoutPadding();
    private static final int SECRET_BYTES = 32;
    private static final int SECRET_LENGTH = 43;                        // 32바이트의 패딩 없는 Base64URL 길이

    /** 토큰 키 저장과 인덱스 등록을 한 번에 해야 인덱스에서 빠진 토큰(폐기 누락)이 생기지 않는다 */
    private static final RedisScript<Long> ISSUE = RedisScript.of("""
            -- KEYS[1]=refresh:{memberId}:{tokenId}, KEYS[2]=refresh:index:{memberId}
            -- ARGV[1]=tokenId, ARGV[2]=TTL(초)
            redis.call('SET', KEYS[1], '1', 'EX', ARGV[2])
            redis.call('SADD', KEYS[2], ARGV[1])
            redis.call('EXPIRE', KEYS[2], ARGV[2])
            return 1
            """, Long.class);

    /*
     * 반환: 1 교체 허용 / 0 없음(만료·위조·로그아웃) / 2 재사용(이미 교체된 토큰)
     * GETDEL 로 꺼내며 지우므로 같은 토큰으로 동시에 여러 번 와도 1은 한 건뿐이다.
     */
    private static final RedisScript<Long> CONSUME = RedisScript.of("""
            -- KEYS[1]=refresh:{memberId}:{tokenId}, KEYS[2]=refresh:index:{memberId}, KEYS[3]=refresh:used:{memberId}:{tokenId}
            -- ARGV[1]=tokenId
            local ttl = redis.call('PTTL', KEYS[1])
            if redis.call('GETDEL', KEYS[1]) then
                redis.call('SREM', KEYS[2], ARGV[1])
                if ttl > 0 then
                    redis.call('SET', KEYS[3], '1', 'PX', ttl)
                end
                return 1
            end
            if redis.call('EXISTS', KEYS[3]) == 1 then
                return 2
            end
            return 0
            """, Long.class);

    private static final RedisScript<Long> REVOKE_ALL = RedisScript.of("""
            -- KEYS[1]=refresh:index:{memberId}, ARGV[1]=토큰 키 접두사 refresh:{memberId}:
            local ids = redis.call('SMEMBERS', KEYS[1])     -- 회원 42의 토큰 ID 목록을 꺼냄
            for _, id in ipairs(ids) do                     -- 하나씩 돌면서
                redis.call('DEL', ARGV[1] .. id)            -- "refresh:42:" .. id → 토큰 키 삭제
            end
            redis.call('DEL', KEYS[1])                      -- 인덱스 Set도 삭제
            return #ids                                     -- 지운 개수 (#은 Lua에서 길이)
            """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final JwtProperties properties;

    /** 새 토큰을 만들어 저장하고 쿠키에 담을 원문을 돌려준다 */
    public String issue(long memberId) {
        byte[] bytes = new byte[SECRET_BYTES];  // 32 바이트만큼
        RANDOM.nextBytes(bytes);    // JWT 를 만드는 대신 SecureRandom 으로 32바이트를 뽑는다.
        String secret = BASE64.encodeToString(bytes);   // 바이트를 문자로 바꿈
        String tokenId = hash(secret);  // 느린 Bcrypt 대신 같은 입력이 같은 조회가 되는 SHA-256 사용
        redisTemplate.execute(ISSUE, List.of(tokenKey(memberId, tokenId), indexKey(memberId)),
                tokenId, String.valueOf(properties.refreshTokenTtl().toSeconds()));
        return memberId + "." + secret;
    }

    /**
     * 토큰을 꺼내며 지운다(회전). 이미 교체된 토큰이 다시 오면 탈취로 보고 그 회원의 토큰을 전부 폐기한다.
     *
     * @return 토큰 주인의 회원 ID
     * @throws BusinessException 형식 오류·만료·위조·재사용이면 401
     */
    public long consume(String refreshToken) {
        ParsedToken token = parse(refreshToken);
        if(token == null) {
            throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        }
        Long result = redisTemplate.execute(CONSUME,
                List.of(tokenKey(token.memberId(), token.tokenId()), indexKey(token.memberId()),
                        usedKey(token.memberId(), token.tokenId())),
                token.tokenId());
        if (result != null && result == 1) {    // 정상적으로 하나의 사용 요청에 대한 memberId 반환
            return token.memberId();
        }
        if (result != null && result == 2) {    // 네트워크 재시도 혹은 공격으로 인한 이중 시도(재사용) 감지, 토큰 전부 폐기
            log.warn("이미 교체된 Refresh Token 재사용. 회원 토큰 전부 폐기 memberId={}", token.memberId());
            revokeAll(token.memberId());
        }
        throw new BusinessException(ErrorCode.UNAUTHENTICATED);
    }

    /** 로그아웃 (13-6 4단계). 이 토큰 하나만 지운다. 형식이 틀리거나 없는 토큰이어도 예외 없이 끝낸다 */
    public void delete(String refreshToken) {
        ParsedToken token = parse(refreshToken);
        if (token == null) {
            return;
        }
        redisTemplate.delete(tokenKey(token.memberId(), token.tokenId()));
        redisTemplate.opsForSet().remove(indexKey(token.memberId()), token.tokenId());
    }

    /** 회원의 Refresh Token 전부 폐기. 재사용 감지, C-09·A-09 임시 비밀번호, 비밀번호 변경 때 쓴다 */
    public void revokeAll(long memberId) {
        redisTemplate.execute(REVOKE_ALL, List.of(indexKey(memberId)), tokenKey(memberId, ""));
    }

    // {memberId}.{secret} 형식이 아니면 null. Redis 를 조회하지 않고 거른다
    private static ParsedToken parse(String refreshToken) {
        int dot = refreshToken.indexOf('.');
        if (dot <= 0 || refreshToken.length() - dot - 1 != SECRET_LENGTH) {
            return null;
        }
        long memberId;
        try {
            memberId = Long.parseLong(refreshToken.substring(0, dot));
        } catch (NumberFormatException e) {
            return null;
        }
        if (memberId <= 0) {
            return null;
        }
        return new ParsedToken(memberId, hash(refreshToken.substring(dot + 1)));
    }

    // 무작위 256비트라 대입이 불가능해 BCrypt 같은 느린 해시가 필요 없다. 같은 입력이 같은 키가 되어야 조회할 수 있다
    static String hash(String secret) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.US_ASCII));
            return BASE64.encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);   // 모든 JVM 에 SHA-256 이 있어 일어나지 않는다
        }
    }

    static String tokenKey(long memberId, String tokenId) {
        return "refresh:" + memberId + ":" + tokenId;
    }

    static String indexKey(long memberId) {
        return "refresh:index:" + memberId;
    }

    static String usedKey(long memberId, String tokenId) {
        return "refresh:used:" + memberId + ":" + tokenId;
    }

    private record ParsedToken(long memberId, String tokenId) {
    }
}
