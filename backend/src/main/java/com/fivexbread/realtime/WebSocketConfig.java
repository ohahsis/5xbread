package com.fivexbread.realtime;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;

/**
 * STOMP over WebSocket (C-03, 13-3). 엔드포인트 /ws, 브로커는 앱 메모리의 Simple Broker(/topic).
 * 앱 3개 사이 전파는 브로커가 아니라 Redis Pub/Sub 으로 한다: 잔여를 바꾼 Lua 가 발행하고 각 앱의 StockBroadcaster 가
 * 받아 자기 연결에 보낸다. SockJS 는 쓰지 않는다(지원 브라우저가 모두 WebSocket 을 쓰고, 쓰면 폴백 경로마다 인증·Nginx 설정이 늘어난다).
 * 토큰은 CONNECT 프레임의 Authorization 헤더로만 받는다. URL 쿼리(?token=)는 Nginx 접근 로그·프록시에 남아 받지 않는다.
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final RealtimeProperties properties;
    private final StompAuthInterceptor authInterceptor;
    private final StompErrorHandler errorHandler;
    private TaskScheduler brokerScheduler;

    public WebSocketConfig(RealtimeProperties properties, StompAuthInterceptor authInterceptor,
                           StompErrorHandler errorHandler) {
        this.properties = properties;
        this.authInterceptor = authInterceptor;
        this.errorHandler = errorHandler;
    }

    /** heartbeat 용. 이 설정이 만드는 messageBrokerTaskScheduler 라 순환을 피하려고 지연 주입한다 (Spring 문서의 방식) */
    @Autowired
    public void setBrokerScheduler(@Lazy @Qualifier("messageBrokerTaskScheduler") TaskScheduler messageBrokerTaskScheduler) {
        this.brokerScheduler = messageBrokerTaskScheduler;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws").setAllowedOriginPatterns(properties.allowedOrigins().toArray(String[]::new));
        registry.setErrorHandler(errorHandler);
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        long heartbeat = properties.heartbeat().toMillis();
        registry.enableSimpleBroker("/topic")
                .setHeartbeatValue(new long[]{heartbeat, heartbeat})
                .setTaskScheduler(brokerScheduler);
        // 클라이언트가 보내는 SEND 는 받지 않는다(StompAuthInterceptor 가 거절). 접두사만 브로커와 겹치지 않게 둔다
        registry.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
        registration.setSendTimeLimit((int) properties.sendTimeLimit().toMillis())
                .setSendBufferSizeLimit((int) properties.sendBufferSizeLimit().toBytes());
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(authInterceptor);
    }
}
