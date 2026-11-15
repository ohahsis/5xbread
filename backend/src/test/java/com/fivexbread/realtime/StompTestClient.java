package com.fivexbread.realtime;

import java.lang.reflect.Type;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.springframework.messaging.converter.ByteArrayMessageConverter;
import org.springframework.messaging.converter.CompositeMessageConverter;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.converter.StringMessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

/**
 * 테스트용 STOMP 클라이언트 하나 = 연결 하나. ERROR 프레임은 message 헤더(오류 코드)를 errors 에 모은다.
 * 서버가 ERROR 뒤에 연결을 닫으면 closed 가 완료된다.
 */
class StompTestClient implements AutoCloseable {

    final BlockingQueue<String> errors = new LinkedBlockingQueue<>();
    final CompletableFuture<Void> closed = new CompletableFuture<>();
    private final WebSocketStompClient client;
    private final CompletableFuture<StompSession> connected = new CompletableFuture<>();

    StompTestClient(int port, String accessToken) {
        client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new CompositeMessageConverter(
                List.of(new ByteArrayMessageConverter(), new StringMessageConverter(), new MappingJackson2MessageConverter())));
        StompHeaders connectHeaders = new StompHeaders();
        if (accessToken != null) {
            connectHeaders.add("Authorization", "Bearer " + accessToken);
        }
        client.connectAsync("ws://localhost:" + port + "/ws", new WebSocketHttpHeaders(), connectHeaders,
                new StompSessionHandlerAdapter() {
                    @Override
                    public void afterConnected(StompSession session, StompHeaders headers) {
                        connected.complete(session);
                    }

                    @Override
                    public Type getPayloadType(StompHeaders headers) {
                        return byte[].class;
                    }

                    @Override
                    public void handleFrame(StompHeaders headers, Object payload) {    // ERROR 프레임
                        errors.add(String.valueOf(headers.getFirst("message")));
                    }

                    @Override
                    public void handleTransportError(StompSession session, Throwable exception) {
                        closed.complete(null);
                    }
                });
    }

    /** CONNECTED 를 받을 때까지 기다린다 */
    StompSession session() throws Exception {
        return connected.get(5, TimeUnit.SECONDS);
    }

    boolean isConnected() {
        try {
            return connected.get(5, TimeUnit.SECONDS).isConnected();
        } catch (Exception e) {
            return false;
        }
    }

    /** 잔여 토픽 구독. 받은 메시지는 반환한 큐에 쌓인다 */
    BlockingQueue<StockMessage> subscribe(String destination) throws Exception {
        BlockingQueue<StockMessage> messages = new LinkedBlockingQueue<>();
        session().subscribe(destination, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return StockMessage.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                messages.add((StockMessage) payload);
            }
        });
        return messages;
    }

    /** 문자열 본문 토픽 구독 (사장님 토픽 확인용) */
    BlockingQueue<String> subscribeText(String destination) throws Exception {
        BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        session().subscribe(destination, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return String.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                messages.add((String) payload);
            }
        });
        return messages;
    }

    /** 첫 ERROR 프레임의 오류 코드. 오지 않으면 null */
    String awaitError() throws InterruptedException {
        return errors.poll(5, TimeUnit.SECONDS);
    }


    @Override
    public void close() {
        connected.thenAccept(session -> {
            if (session.isConnected()) {
                session.disconnect();
            }
        });
        client.stop();
    }
}
