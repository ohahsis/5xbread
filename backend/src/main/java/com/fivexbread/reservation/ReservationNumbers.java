package com.fivexbread.reservation;

import com.fivexbread.saledate.StockKeys;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * 예약번호 MMDD-NNNN, 판매일별 0001부터 (9장 필드 규격). 키: resv_no:{date}.
 * 앱 3개가 겹치지 않게 Redis INCR 로 매긴다. 키가 없으면 DB 의 마지막 번호로 SET NX 해서 채운다(재고 키와 같은 방식).
 * DB 저장이 실패한 번호는 다시 쓰지 않아 번호가 건너뛸 수 있다. 겹치면 uk_reservation_no 가 막는다.
 */
@Component
@RequiredArgsConstructor
public class ReservationNumbers {

    private static final int MAX_SEQUENCE = 9999;

    private static final RedisScript<Long> NEXT = RedisScript.of("""
            if redis.call('EXISTS', KEYS[1]) == 0 then return -1 end
            return redis.call('INCR', KEYS[1])
            """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final ReservationRepository reservationRepository;
    private final Clock clock;

    public String next(LocalDate saleDate) {
        String key = "resv_no:{" + saleDate + "}";
        for (int attempt = 0; attempt < 3; attempt++) {
            Long sequence = redisTemplate.execute(NEXT, List.of(key));
            if (sequence != null && sequence > 0) {
                if (sequence > MAX_SEQUENCE) {
                    throw new IllegalStateException("판매일 예약번호가 9999를 넘음 saleDate=" + saleDate);
                }
                return Reservation.formatNo(saleDate, sequence);
            }
            String last = reservationRepository.findLastReservationNo(saleDate);
            long lastSequence = last == null ? 0 : Long.parseLong(last.substring(last.indexOf('-') + 1));
            redisTemplate.opsForValue().setIfAbsent(key, String.valueOf(lastSequence),
                    StockKeys.ttl(saleDate, LocalDateTime.now(clock)));
        }
        throw new IllegalStateException("예약번호 키를 채운 뒤에도 키가 없음 saleDate=" + saleDate);
    }
}
