package com.fivexbread.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fivexbread.realtime.StockMessage.Change;
import java.io.IOException;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.locks.ReentrantLock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.SmartLifecycle;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

/**
 * Redis 의 잔여 변경을 이 앱의 STOMP 구독자에게 (13-3 4·5단계).
 * 1. channel:stock:{판매일} 메시지를 받아 판매일·빵별로 버전이 가장 큰 값만 남긴다
 * 2. flushInterval(200ms)마다 버퍼를 통째로 꺼내 판매일별로 한 번 보낸다. 묶음의 version 은 그 안의 가장 큰 버전
 *
 * 묶음 version 이 단조 증가하려면 "꺼내기"와 "쌓기"가 겹치지 않아야 한다. 빵별로 하나씩 꺼내는 사이에 새 메시지가 쌓이면
 * 빵 A(버전 10)는 다음 묶음으로, 빵 B(버전 11)는 이번 묶음으로 가서 다음 묶음의 version(10)이 이번(11)보다 작아지고,
 * 화면이 그 묶음을 버려 A 가 갱신되지 않는다. 그래서 쌓기와 꺼내기를 같은 락으로 감싸고, 꺼낼 때는 맵을 통째로 바꾼다.
 * 메시지도 받은 순서(= Redis 발행 순서 = 버전 순서)로 처리해야 해서 리스너 컨테이너는 스레드 1개로 전달한다 (RealtimeRedisConfig).
 * 락을 잡는 시간은 맵 하나에 넣기·바꾸기뿐이라 짧다.
 */
@Slf4j
@Component
public class StockBroadcaster implements MessageListener, SmartLifecycle {

    private final ObjectMapper objectMapper;
    private final SimpMessagingTemplate messagingTemplate;
    private final TaskScheduler scheduler;
    private final RealtimeProperties properties;
    private final ReentrantLock lock = new ReentrantLock();
    private Map<LocalDate, Map<Long, Pending>> buffer = new HashMap<>();   // lock 으로 보호
    private volatile ScheduledFuture<?> flushTask;

    public StockBroadcaster(ObjectMapper objectMapper, SimpMessagingTemplate messagingTemplate,
                            @Qualifier("messageBrokerTaskScheduler") TaskScheduler scheduler,
                            RealtimeProperties properties) {
        this.objectMapper = objectMapper;
        this.messagingTemplate = messagingTemplate;
        this.scheduler = scheduler;
        this.properties = properties;
    }

    /** Lua(StockRedisSync.LUA_PUBLISH_STOCK)가 발행한 메시지 */
    record Published(LocalDate saleDate, long version, List<PublishedChange> changes) {
    }

    record PublishedChange(long breadId, int remaining) {
    }

    private record Pending(int remaining, long version) {
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        Published published;
        try {
            published = objectMapper.readValue(message.getBody(), Published.class);
        } catch (IOException e) {
            log.warn("읽을 수 없는 잔여 변경 메시지를 버림", e);
            return;
        }
        accept(published);
    }

    void accept(Published published) {
        lock.lock();
        try {
            Map<Long, Pending> breads = buffer.computeIfAbsent(published.saleDate(), date -> new HashMap<>());
            for (PublishedChange change : published.changes()) {
                breads.merge(change.breadId(), new Pending(change.remaining(), published.version()),
                        (old, now) -> old.version() >= now.version() ? old : now);
            }
        } finally {
            lock.unlock();
        }
    }

    /** 버퍼를 비워 판매일별로 보낸다. 바뀐 빵이 없으면 보내지 않는다 */
    void flush() {
        Map<LocalDate, Map<Long, Pending>> drained;
        lock.lock();
        try {
            if (buffer.isEmpty()) {
                return;
            }
            drained = buffer;
            buffer = new HashMap<>();
        } finally {
            lock.unlock();
        }
        drained.forEach((date, breads) -> {
            long version = breads.values().stream().mapToLong(Pending::version).max().orElse(0);
            List<Change> changes = breads.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey(Comparator.naturalOrder()))
                    .map(entry -> new Change(entry.getKey(), entry.getValue().remaining()))
                    .toList();
            try {
                messagingTemplate.convertAndSend(StompAuthInterceptor.stockTopic(date), new StockMessage(version, changes));
            } catch (RuntimeException e) {
                log.warn("잔여 변경 전송 실패. 다음 변경·재연결 스냅샷이 맞춤 saleDate={} version={}", date, version, e);
            }
        });
    }

    @Override
    public void start() {
        flushTask = scheduler.scheduleWithFixedDelay(this::flush, properties.flushInterval());
    }

    @Override
    public void stop() {
        ScheduledFuture<?> task = flushTask;
        if (task != null) {
            task.cancel(false);
            flushTask = null;
        }
    }

    @Override
    public boolean isRunning() {
        return flushTask != null;
    }
}
