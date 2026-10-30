package com.fivexbread.reservation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fivexbread.common.BusinessException;
import com.fivexbread.common.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * 예약 중복 제출 방지 (13-1 3단계, N-06, decisions.md 6장 "C-04 Idempotency 저장"). 키: idem:{Idempotency-Key}, TTL 24시간.
 * - 시작: SET NX 로 "PROCESSING:{지문}"을 넣는다. 지문 = SHA-256(memberId + 요청 본문)
 * - 성공(201)만 커밋 후 {지문, 201, Location, 본문}으로 바꿔 저장한다. 같은 지문의 재요청은 저장된 201 을 그대로 돌려준다
 * - 실패(422·409·500)는 키를 지워 같은 키로 다시 시도할 수 있게 한다. 실패는 부작용이 없고, 재고 부족은 취소가 생기면 성공할 수 있다
 * - 처리 중이면 409 IDEMPOTENCY_IN_PROGRESS, 지문이 다르면(다른 회원·다른 본문) 422 IDEMPOTENCY_KEY_MISMATCH
 */
@Component
@RequiredArgsConstructor
public class ReservationIdempotency {

    private static final String PROCESSING = "PROCESSING:";

    // 내가 넣은 PROCESSING 값일 때만 지운다. 그사이 다른 값으로 바뀌었으면 남긴다
    private static final RedisScript<Long> RELEASE = RedisScript.of("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then
              return redis.call('DEL', KEYS[1])
            end
            return 0
            """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final ReservationProperties properties;

    /** 저장된 201 응답 */
    public record StoredResponse(String fingerprint, int status, String location, JsonNode body) {
    }

    public String fingerprint(long memberId, ReservationCreateRequest request) {
        try {
            String source = memberId + ":" + objectMapper.writeValueAsString(request);
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("요청 지문을 만들 수 없음", e);
        }
    }

    /**
     * 처음이면 PROCESSING 으로 표시하고 빈 값을, 이미 성공한 요청이면 저장된 응답을 돌려준다.
     * 처리 중(409)·지문 불일치(422)는 예외. 이 경우 키는 남의 것이라 지우지 않는다.
     */
    public Optional<StoredResponse> begin(String key, String fingerprint) {
        for (int attempt = 0; attempt < 3; attempt++) {
            Boolean created = redisTemplate.opsForValue()
                    .setIfAbsent(redisKey(key), PROCESSING + fingerprint, properties.idempotencyTtl()); // 처음
            if (Boolean.TRUE.equals(created)) {
                return Optional.empty();
            }
            String value = redisTemplate.opsForValue().get(redisKey(key));
            if (value == null) {
                continue;                                  // 그사이 지워졌거나 만료됨 → 다시 시도
            }
            if (value.startsWith(PROCESSING)) {
                throw new BusinessException(value.equals(PROCESSING + fingerprint)
                        ? ErrorCode.IDEMPOTENCY_IN_PROGRESS : ErrorCode.IDEMPOTENCY_KEY_MISMATCH);  // 처리중 or 지문 불일치
            }
            StoredResponse stored = read(value);
            if (!stored.fingerprint().equals(fingerprint)) {
                throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_MISMATCH);
            }
            return Optional.of(stored);
        }
        throw new BusinessException(ErrorCode.IDEMPOTENCY_IN_PROGRESS);
    }

    /** 커밋 후 성공 응답을 저장한다. TTL 은 다시 24시간 */
    public void complete(String key, String fingerprint, String location, Object body) {
        StoredResponse stored = new StoredResponse(fingerprint, 201, location, objectMapper.valueToTree(body));
        try {
            redisTemplate.opsForValue().set(redisKey(key), objectMapper.writeValueAsString(stored),
                    properties.idempotencyTtl());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Idempotency 응답을 저장할 수 없음", e);
        }
    }

    /** 실패한 요청의 표시를 지워 같은 키로 다시 시도할 수 있게 한다 */
    public void release(String key, String fingerprint) {
        redisTemplate.execute(RELEASE, List.of(redisKey(key)), PROCESSING + fingerprint);
    }

    private StoredResponse read(String value) {
        try {
            return objectMapper.readValue(value, StoredResponse.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("저장된 Idempotency 응답을 읽을 수 없음", e);
        }
    }

    static String redisKey(String key) {
        return "idem:" + key;
    }
}
