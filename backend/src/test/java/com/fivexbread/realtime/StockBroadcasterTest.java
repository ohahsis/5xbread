package com.fivexbread.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fivexbread.realtime.StockBroadcaster.Published;
import com.fivexbread.realtime.StockBroadcaster.PublishedChange;
import com.fivexbread.realtime.StockMessage.Change;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.connection.DefaultMessage;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.util.unit.DataSize;
import com.fivexbread.support.Concurrently;
import java.util.concurrent.Callable;

/** 200ms 묶음 (13-3 4·5단계). 스케줄러 없이 accept·flush 를 직접 부른다 */
class StockBroadcasterTest {

    private static final LocalDate DATE = LocalDate.of(2026, 10, 10);
    private static final LocalDate OTHER = LocalDate.of(2026, 10, 11);

    private final SimpMessagingTemplate template = mock(SimpMessagingTemplate.class);
    private final StockBroadcaster broadcaster = new StockBroadcaster(
            new ObjectMapper().registerModule(new JavaTimeModule()), template, mock(TaskScheduler.class),
            new RealtimeProperties(List.of(), Duration.ofMillis(200), Duration.ofSeconds(10), Duration.ofSeconds(10),
                    DataSize.ofKilobytes(512), 100));

    @Test
    void 같은_빵은_가장_큰_버전의_값만_남고_판매일별로_한_번씩_보낸다() {
        broadcaster.accept(published(DATE, 1, 3, 47));
        broadcaster.accept(published(DATE, 3, 3, 40));
        broadcaster.accept(published(DATE, 2, 3, 44));            // 늦게 도착한 옛 버전은 덮지 않는다
        broadcaster.accept(published(DATE, 4, 1, 28));
        broadcaster.accept(published(OTHER, 1, 3, 9));

        broadcaster.flush();

        verify(template).convertAndSend("/topic/sale-dates/2026-10-10/stock",
                new StockMessage(4, List.of(new Change(1, 28), new Change(3, 40))));
        verify(template).convertAndSend("/topic/sale-dates/2026-10-11/stock",
                new StockMessage(1, List.of(new Change(3, 9))));
    }

    @Test
    void 비운_뒤에는_보내지_않고_새로_온_것만_다음_묶음으로_간다() {
        broadcaster.accept(published(DATE, 1, 3, 47));
        broadcaster.flush();
        broadcaster.flush();
        broadcaster.accept(published(DATE, 2, 1, 29));
        broadcaster.flush();

        ArgumentCaptor<Object> sent = ArgumentCaptor.forClass(Object.class);
        verify(template, org.mockito.Mockito.times(2)).convertAndSend(anyString(), sent.capture());
        assertThat(sent.getAllValues()).containsExactly(
                new StockMessage(1, List.of(new Change(3, 47))),
                new StockMessage(2, List.of(new Change(1, 29))));
    }

    @Test
    void Lua_가_발행한_JSON_을_읽는다() {
        String json = "{\"saleDate\":\"2026-10-10\",\"version\":12,\"changes\":[{\"breadId\":3,\"remaining\":0}]}";

        broadcaster.onMessage(new DefaultMessage("channel:stock:2026-10-10".getBytes(StandardCharsets.UTF_8),
                json.getBytes(StandardCharsets.UTF_8)), null);
        broadcaster.flush();

        verify(template).convertAndSend("/topic/sale-dates/2026-10-10/stock",
                new StockMessage(12, List.of(new Change(3, 0))));
    }

    @Test
    void 읽을_수_없는_메시지는_버린다() {
        broadcaster.onMessage(new DefaultMessage(new byte[0], "{".getBytes(StandardCharsets.UTF_8)), null);
        broadcaster.flush();

        verify(template, never()).convertAndSend(anyString(), any(Object.class));
    }

    @Test
    void 쌓기와_비우기가_동시에_일어나도_묶음_버전은_줄지_않고_빵별_마지막_값이_빠지지_않는다() throws Exception {
        List<StockMessage> sent = java.util.Collections.synchronizedList(new ArrayList<>());
        SimpMessagingTemplate recording = mock(SimpMessagingTemplate.class);
        org.mockito.Mockito.doAnswer(invocation -> sent.add(invocation.getArgument(1)))
                .when(recording).convertAndSend(anyString(), any(Object.class));
        StockBroadcaster target = new StockBroadcaster(new ObjectMapper().registerModule(new JavaTimeModule()),
                recording, mock(TaskScheduler.class), new RealtimeProperties(List.of(), Duration.ofMillis(200),
                Duration.ofSeconds(10), Duration.ofSeconds(10), DataSize.ofKilobytes(512), 100));
        int total = 20_000;
        AtomicLong done = new AtomicLong();

        List<Callable<Void>> tasks = new ArrayList<>();
        tasks.add(() -> {                                         // 리스너 스레드 1개: 버전 순서대로 빵 1·2 번갈아
            for (int version = 1; version <= total; version++) {
                target.accept(published(DATE, version, version % 2 + 1, version));
            }
            done.set(1);
            return null;
        });
        tasks.add(() -> {                                         // 묶음 전송 스레드
            while (done.get() == 0) {
                target.flush();
            }
            return null;
        });
        Concurrently.run(tasks, 2);
        target.flush();

        assertThat(sent).extracting(StockMessage::version).isSorted();
        assertThat(sent.get(sent.size() - 1).version()).isEqualTo(total);
        java.util.Map<Long, Integer> last = new java.util.HashMap<>();
        sent.forEach(message -> message.changes().forEach(change -> last.put(change.breadId(), change.remaining())));
        assertThat(last).containsEntry(1L, total).containsEntry(2L, total - 1);
    }

    private static Published published(LocalDate date, long version, long breadId, int remaining) {
        return new Published(date, version, List.of(new PublishedChange(breadId, remaining)));
    }
}
