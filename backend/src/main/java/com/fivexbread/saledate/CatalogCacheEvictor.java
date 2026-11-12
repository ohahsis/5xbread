package com.fivexbread.saledate;

import com.fivexbread.catalog.CatalogChanged;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 앱 3개의 로컬 캐시 무효화 (13-4 8단계). 커밋 뒤에 자기 캐시를 비우고 channel:cache-evict 로 다른 앱에 알린다.
 * 커밋 전에 비우면 다른 요청이 옛 값을 다시 캐시한다. 자기 캐시를 먼저 비우므로 Redis 가 죽어도 저장한 앱은 바로 맞고,
 * 메시지가 유실돼도 다른 앱은 TTL(30초) 뒤에 맞는다 (decisions.md 6장 "C-02 캐시 무효화").
 * 메시지는 판매일(2026-10-10) 또는 전체(*)이고, 자기가 보낸 메시지도 받지만 다시 비울 뿐이다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CatalogCacheEvictor implements MessageListener {

    static final String CHANNEL = "channel:cache-evict";
    private static final String ALL_DATES = "*";

    private final SaleCatalogCache cache;
    private final StringRedisTemplate redisTemplate;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onChanged(CatalogChanged event) {
        String message = event.saleDate() == null ? ALL_DATES : event.saleDate().toString();
        evict(message);
        try {
            redisTemplate.convertAndSend(CHANNEL, message);
        } catch (RuntimeException e) {
            log.warn("캐시 무효화 발행 실패. 다른 앱은 TTL 뒤에 반영됨 message={}", message, e);
        }
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        evict(new String(message.getBody(), StandardCharsets.UTF_8));
    }

    private void evict(String message) {
        if (ALL_DATES.equals(message)) {
            cache.evictAll();
            return;
        }
        try {
            cache.evict(LocalDate.parse(message));
        } catch (DateTimeParseException e) {
            log.warn("알 수 없는 캐시 무효화 메시지를 무시함");
        }
    }
}
