package com.fivexbread.saledate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fivexbread.common.BusinessException;
import com.fivexbread.common.ClockConfig;
import com.fivexbread.saledate.SaleBreadsResponse.SaleBreadResponse;
import com.fivexbread.saledate.StockReader.Snapshot;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.RedisSystemException;

/** 빵 목록의 Redis 장애 분기 (decisions.md 6장 "C-02 잔여 읽기"). 통합 테스트는 공유 Redis 를 끌 수 없어 단위로 본다 */
class SaleBreadsFacadeTest {

    private static final LocalDate DATE = LocalDate.of(2026, 12, 1);
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 28, 11, 0);
    private static final List<Long> IDS = List.of(1L, 2L);
    private static final SaleDateSchedule OPEN = new SaleDateSchedule(NOW.minusHours(1), NOW.plusDays(2), false);
    private static final SaleDateSchedule CLOSED = new SaleDateSchedule(NOW.minusDays(1), NOW.minusHours(1), false);

    private final SaleCatalogCache cache = mock(SaleCatalogCache.class);
    private final StockReader reader = mock(StockReader.class);
    private final StockRedisSync redisSync = mock(StockRedisSync.class);
    private final SaleDateQueryService query = mock(SaleDateQueryService.class);
    private final SaleBreadsFacade facade = new SaleBreadsFacade(cache, reader, redisSync, query,
            Clock.fixed(NOW.atZone(ClockConfig.KST).toInstant(), ClockConfig.KST));

    @BeforeEach
    void setUp() {
        when(cache.schedule(DATE)).thenReturn(Optional.of(OPEN));
        when(cache.breads(DATE)).thenReturn(List.of(new SaleBreadRow(1L, "소금빵", 3500, 4, 1),
                new SaleBreadRow(2L, "크로와상", 4200, null, 2)));
        when(query.remaining(DATE, IDS)).thenReturn(Map.of(1L, 30, 2L, 0));
        when(query.myQuantities(DATE, 7L)).thenReturn(Map.of(1L, 2));
    }

    @Test
    void Redis_연결_실패면_DB_값으로_200이고_적재를_시도하지_않는다() {
        when(reader.read(DATE, IDS, 7L)).thenThrow(new RedisConnectionFailureException("down"));

        List<SaleBreadResponse> breads = facade.breads(DATE, 7L).breads();

        assertThat(breads.get(0).remaining()).isEqualTo(30);
        assertThat(breads.get(0).myReservedQty()).isEqualTo(2);
        assertThat(breads.get(1).status()).isEqualTo(SaleBreadStatus.SOLD_OUT);
        verify(redisSync, never()).fillMissing(any(), anyMap());
    }

    @Test
    void Redis_명령_시간_초과도_DB_값으로_응답한다() {
        when(reader.read(DATE, IDS, null)).thenThrow(new QueryTimeoutException("timeout"));

        assertThat(facade.breads(DATE, null).breads().get(0).remaining()).isEqualTo(30);
    }

    @Test
    void Redis가_복구되면_다시_Redis_값을_쓴다() {
        when(reader.read(DATE, IDS, null)).thenThrow(new RedisConnectionFailureException("down"))
                .thenReturn(new Snapshot(7, Map.of(1L, 5, 2L, 1), null));

        facade.breads(DATE, null);

        assertThat(facade.breads(DATE, null).breads().get(0).remaining()).isEqualTo(5);
    }

    @Test
    void 장애가_아닌_Redis_오류는_숨기지_않는다() {
        when(reader.read(DATE, IDS, null)).thenThrow(new RedisSystemException("WRONGTYPE", new RuntimeException()));

        assertThatThrownBy(() -> facade.breads(DATE, null)).isInstanceOf(RedisSystemException.class);
    }

    @Test
    void 키_적재가_실패해도_DB_값으로_응답한다() {
        when(reader.read(DATE, IDS, null)).thenReturn(Snapshot.EMPTY);
        doThrow(new RedisConnectionFailureException("down")).when(redisSync).fillMissing(eq(DATE), anyMap());

        assertThat(facade.breads(DATE, null).breads().get(0).remaining()).isEqualTo(30);
    }

    @Test
    void 마감된_판매일은_키를_적재하지_않는다() {
        when(cache.schedule(DATE)).thenReturn(Optional.of(CLOSED));
        when(reader.read(DATE, IDS, null)).thenReturn(Snapshot.EMPTY);

        assertThat(facade.breads(DATE, null).status()).isEqualTo(SaleDateStatus.CLOSED);

        verify(redisSync, never()).fillMissing(any(), anyMap());
    }

    @Test
    void 없는_판매일은_404이고_수량이_없으면_Redis를_부르지_않는다() {
        when(cache.schedule(DATE)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> facade.breads(DATE, null)).isInstanceOf(BusinessException.class);

        when(cache.schedule(DATE)).thenReturn(Optional.of(OPEN));
        when(cache.breads(DATE)).thenReturn(List.of());
        assertThat(facade.breads(DATE, null).breads()).isEmpty();
        verify(reader, never()).read(any(), any(), any());
    }
}
