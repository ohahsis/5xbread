package com.fivexbread.saledate;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/** channel:cache-evict 구독. 끊기면 컨테이너가 다시 구독하고, 그사이 메시지는 유실돼 TTL 로 회복한다 */
@Configuration
public class CatalogCacheConfig {

    @Bean
    public RedisMessageListenerContainer catalogCacheListenerContainer(RedisConnectionFactory connectionFactory,
                                                                       CatalogCacheEvictor evictor) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(evictor, ChannelTopic.of(CatalogCacheEvictor.CHANNEL));
        return container;
    }
}
