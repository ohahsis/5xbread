package com.fivexbread.saledate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fivexbread.support.Concurrently;
import com.fivexbread.support.MutableClock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 로컬 캐시의 single-flight·TTL·무효화 (13-4 꼬리 질문). DB 호출 수를 세려고 저장소를 mock 으로 둔다 */
class SaleCatalogCacheTest {

    private static final LocalDate DATE = LocalDate.of(2026, 12, 1);
    private static final LocalDate OTHER = DATE.plusDays(1);
    private static final List<SaleBreadRow> ROWS = List.of(new SaleBreadRow(1L, "소금빵", 3500, 4, 1));

    private final SaleDateRepository saleDateRepository = mock(SaleDateRepository.class);
    private final DailyStockRepository dailyStockRepository = mock(DailyStockRepository.class);
    private final MutableClock clock = new MutableClock();
    private final LocalDateTime start = LocalDateTime.of(2026, 11, 28, 10, 0);
    private SaleCatalogCache cache;

    @BeforeEach
    void setUp() {
        clock.set(start);
        cache = new SaleCatalogCache(saleDateRepository, dailyStockRepository,
                new CatalogCacheProperties(Duration.ofSeconds(30)), clock);
        when(dailyStockRepository.findSaleBreads(any())).thenReturn(ROWS);
        when(saleDateRepository.findById(any())).thenReturn(Optional.empty());
    }

    @Test
    void 같은_키를_동시에_읽어도_DB는_1번만_조회한다() throws Exception {
        when(dailyStockRepository.findSaleBreads(DATE)).thenAnswer(invocation -> {
            Thread.sleep(200);          // 로딩이 끝나기 전에 나머지 스레드가 모두 도착하게
            return ROWS;
        });
        when(saleDateRepository.findById(DATE)).thenAnswer(invocation -> {
            Thread.sleep(200);
            return Optional.empty();
        });
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < 32; i++) {
            tasks.add(() -> cache.breads(DATE));
            tasks.add(() -> cache.schedule(DATE));
        }

        List<Object> results = Concurrently.run(tasks, 64);

        assertThat(results).hasSize(64).doesNotContainNull();
        verify(dailyStockRepository, times(1)).findSaleBreads(DATE);
        verify(saleDateRepository, times(1)).findById(DATE);
    }

    @Test
    void 만료되면_다시_읽는다() {
        cache.breads(DATE);

        clock.set(start.plusSeconds(29));
        cache.breads(DATE);
        verify(dailyStockRepository, times(1)).findSaleBreads(DATE);

        clock.set(start.plusSeconds(31));
        cache.breads(DATE);
        verify(dailyStockRepository, times(2)).findSaleBreads(DATE);
    }

    @Test
    void 없는_판매일도_캐시해_반복_호출이_DB로_가지_않는다() {
        assertThat(cache.schedule(DATE)).isEmpty();
        assertThat(cache.schedule(DATE)).isEmpty();

        verify(saleDateRepository, times(1)).findById(DATE);
    }

    @Test
    void 판매일_무효화는_그_날만_전체_무효화는_모두_비운다() {
        cache.breads(DATE);
        cache.breads(OTHER);

        cache.evict(DATE);
        cache.breads(DATE);
        cache.breads(OTHER);
        verify(dailyStockRepository, times(2)).findSaleBreads(DATE);
        verify(dailyStockRepository, times(1)).findSaleBreads(OTHER);

        cache.evictAll();
        cache.breads(OTHER);
        verify(dailyStockRepository, times(2)).findSaleBreads(OTHER);
    }
}
