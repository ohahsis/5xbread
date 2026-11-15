package com.fivexbread.realtime;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

/**
 * 실시간 잔여 알림 (C-03, 13-3).
 *
 * @param allowedOrigins     WebSocket 핸드셰이크를 허용할 Origin 패턴. 비어 있으면 같은 출처만 (Origin 헤더가 없는 비브라우저 클라이언트는 항상 허용)
 * @param flushInterval      앱마다 빵별 최신 잔여를 모았다가 보내는 간격 (13-3 5단계: 200ms)
 * @param heartbeat          STOMP heartbeat. Nginx proxy_read_timeout(3600s)보다 짧아야 유휴 연결이 끊기지 않는다 (13-3 7단계: 10초)
 * @param sendTimeLimit      한 메시지 전송이 이 시간을 넘기면 연결을 끊는다 (느린 클라이언트)
 * @param sendBufferSizeLimit 못 보낸 메시지가 연결별로 이만큼 쌓이면 연결을 끊는다
 */
@Validated
@ConfigurationProperties("fivexbread.realtime")
public record RealtimeProperties(
        List<String> allowedOrigins,
        @NotNull Duration flushInterval,
        @NotNull Duration heartbeat,
        @NotNull Duration sendTimeLimit,
        @NotNull DataSize sendBufferSizeLimit,
        @Positive int listenerQueueCapacity
) {

    public RealtimeProperties {
        allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
    }
}
