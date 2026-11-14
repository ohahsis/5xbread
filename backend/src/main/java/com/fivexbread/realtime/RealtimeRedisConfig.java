package com.fivexbread.realtime;

import com.fivexbread.saledate.StockKeys;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.scheduling.concurrent.CustomizableThreadFactory;

/**
 * channel:stock:* 구독 (13-3 4단계). 끊기면 컨테이너가 다시 구독하고, 그사이 메시지는 유실된다(at-most-once).
 * 화면은 다음 메시지(절대값)나 재연결 스냅샷으로 맞춘다.
 *
 * 전달 스레드를 1개로 둔다. 기본 실행기(SimpleAsyncTaskExecutor)는 메시지마다 새 스레드라 처리 순서가 바뀌고,
 * 그러면 StockBroadcaster 의 묶음 version 이 뒤로 갈 수 있다. Lettuce 의 I/O 스레드에서 바로 처리(SyncTaskExecutor)하지 않는 것은
 * 다른 Redis 명령의 응답 처리와 같은 스레드를 쓰기 때문이다. 대기열이 차면(앱이 멈춘 수준) 가장 오래된 메시지를 버린다.
 * 실행기를 빈으로 두지 않는 이유: Executor 빈이 생기면 Spring Boot 의 기본 applicationTaskExecutor 가 만들어지지 않는다.
 */
@Configuration
public class RealtimeRedisConfig implements DisposableBean {

    private final ThreadPoolExecutor listenerExecutor;

    public RealtimeRedisConfig(RealtimeProperties properties) {
        this.listenerExecutor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(properties.listenerQueueCapacity()),
                new CustomizableThreadFactory("stock-listener-"),
                new ThreadPoolExecutor.DiscardOldestPolicy());
    }

    @Bean
    public RedisMessageListenerContainer stockListenerContainer(RedisConnectionFactory connectionFactory,
                                                                StockBroadcaster broadcaster) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.setTaskExecutor(listenerExecutor);
        container.addMessageListener(broadcaster, PatternTopic.of(StockKeys.STOCK_CHANNEL_PATTERN));
        return container;
    }

    @Override
    public void destroy() {
        listenerExecutor.shutdownNow();
    }
}
