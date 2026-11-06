package com.fivexbread.saledate;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/**
 * 판매일 설정과 빵 카탈로그의 앱별 로컬 캐시 (13-4 2·3단계). 키는 판매일이고 값은 불변이다.
 * get(key, loader) 는 같은 키의 동시 로딩을 한 번으로 묶어, 만료 순간에 DB 로 몰리지 않는다.
 * 없는 판매일도 Optional.empty 로 캐시한다. 없는 날짜를 반복 호출해도 DB 로 가지 않고, 판매일을 만들면 무효화된다.
 * 시간은 주입한 Clock 으로 재서 테스트가 TTL 을 옮길 수 있다.
 */
@Component
public class SaleCatalogCache {

    private static final int MAX_DATES = 100;       // 예약 가능한 판매일은 60일 안이라 충분하다

    private final Cache<LocalDate, Optional<SaleDateSchedule>> schedules;
    private final Cache<LocalDate, List<SaleBreadRow>> breads;
    private final SaleDateRepository saleDateRepository;
    private final DailyStockRepository dailyStockRepository;

    public SaleCatalogCache(SaleDateRepository saleDateRepository, DailyStockRepository dailyStockRepository,
                            CatalogCacheProperties properties, Clock clock) {
        this.saleDateRepository = saleDateRepository;
        this.dailyStockRepository = dailyStockRepository;
        this.schedules = newCache(properties, clock);
        this.breads = newCache(properties, clock);
    }

    private static <V> Cache<LocalDate, V> newCache(CatalogCacheProperties properties, Clock clock) {
        return Caffeine.newBuilder()
                .expireAfterWrite(properties.ttl())
                .maximumSize(MAX_DATES)
                .ticker(() -> TimeUnit.MILLISECONDS.toNanos(clock.millis()))
                .build();
    }

    public Optional<SaleDateSchedule> schedule(LocalDate date) {
        return schedules.get(date, key -> saleDateRepository.findById(key).map(SaleDate::schedule));
    }

    public List<SaleBreadRow> breads(LocalDate date) {
        return breads.get(date, dailyStockRepository::findSaleBreads);
    }

    public void evict(LocalDate date) {
        schedules.invalidate(date);
        breads.invalidate(date);
    }

    public void evictAll() {
        schedules.invalidateAll();
        breads.invalidateAll();
    }
}
