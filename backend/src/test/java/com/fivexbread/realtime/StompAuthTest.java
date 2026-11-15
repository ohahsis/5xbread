package com.fivexbread.realtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fivexbread.auth.JwtProvider;
import com.fivexbread.member.Role;
import com.fivexbread.realtime.StockMessage.Change;
import com.fivexbread.support.IntegrationTestSupport;
import com.fivexbread.support.MutableClock;
import com.fivexbread.support.MutableClockConfig;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.messaging.simp.SimpMessagingTemplate;

/**
 * STOMP 인증·권한 (13-3 1단계, decisions.md 6장 "C-03 인증 범위"). 거절은 ERROR 프레임(message = 오류 코드) 뒤 연결 종료.
 * 구독이 됐는지는 서버가 그 토픽에 보낸 메시지를 받는지로 본다 (Simple Broker 는 SUBSCRIBE 에 RECEIPT 를 주지 않는다).
 */
@Import(MutableClockConfig.class)
class StompAuthTest extends IntegrationTestSupport {

    private static final String STOCK_TOPIC = "/topic/sale-dates/2026-10-10/stock";

    @LocalServerPort
    int port;

    @Autowired
    JwtProvider jwtProvider;

    @Autowired
    MutableClock mutableClock;

    @Autowired
    SimpMessagingTemplate messagingTemplate;

    private final List<StompTestClient> clients = new ArrayList<>();

    @AfterEach
    void tearDown() {
        clients.forEach(StompTestClient::close);
        mutableClock.reset();
    }

    @Test
    void 토큰_없이_연결해_잔여_토픽을_구독하고_받는다() throws Exception {
        StompTestClient anonymous = client(null);

        assertReceivesStock(anonymous);
    }

    @Test
    void 손님_토큰으로_연결해_잔여_토픽을_받는다() throws Exception {
        assertReceivesStock(client(jwtProvider.issue(7L, Role.CUSTOMER, false)));
    }

    @Test
    void 만료되거나_위조된_토큰은_익명으로_연결돼_잔여는_받고_사장님_토픽은_거절된다() throws Exception {
        mutableClock.set(LocalDateTime.now().minusHours(1));
        String expiredAdmin = jwtProvider.issue(1L, Role.ADMIN, false);  // 30분 만에 만료
        mutableClock.reset();

        assertReceivesStock(client(expiredAdmin));
        assertReceivesStock(client("not-a-jwt"));

        StompTestClient expired = client(expiredAdmin);
        expired.session().subscribe("/topic/admin/reservations", new NoopHandler());
        assertThat(expired.awaitError()).isEqualTo("FORBIDDEN");
    }

    @Test
    void 비밀번호_변경이_필요한_토큰은_CONNECT_에서_거절된다() throws Exception {
        StompTestClient client = new StompTestClient(port, jwtProvider.issue(7L, Role.CUSTOMER, true));
        clients.add(client);

        assertThat(client.awaitError()).isEqualTo("PASSWORD_CHANGE_REQUIRED");
        assertThat(client.isConnected()).isFalse();
    }

    @Test
    void 손님이_사장님_토픽을_구독하면_FORBIDDEN_후_연결이_끊긴다() throws Exception {
        StompTestClient customer = client(jwtProvider.issue(7L, Role.CUSTOMER, false));

        customer.session().subscribe("/topic/admin/reservations", new NoopHandler());

        assertThat(customer.awaitError()).isEqualTo("FORBIDDEN");
        customer.closed.get(5, TimeUnit.SECONDS);
    }

    @Test
    void 사장님은_사장님_토픽을_구독한다() throws Exception {
        StompTestClient admin = client(jwtProvider.issue(1L, Role.ADMIN, false));
        BlockingQueue<String> messages = admin.subscribeText("/topic/admin/reservations");

        String received = null;
        for (int i = 0; i < 20 && received == null; i++) {               // 구독 등록 전에 보낸 메시지는 버려지므로 받을 때까지 보낸다
            messagingTemplate.convertAndSend("/topic/admin/reservations", "hello");
            received = messages.poll(250, TimeUnit.MILLISECONDS);
        }
        assertThat(received).isEqualTo("hello");
        assertThat(admin.errors).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/topic/sale-dates/2026-02-30/stock",       // 없는 날짜
            "/topic/sale-dates/20261010/stock",         // 형식
            "/topic/sale-dates/*/stock",                // Simple Broker 는 구독 목적지를 패턴으로 비교한다
            "/topic/sale-dates/**",
            "/topic/admin",                             // 사장님 토픽 접두사가 아니다
            "/queue/anything",
            "/topic/sale-dates/2026-10-10/stock/../x"})
    void 허용_목록에_없는_목적지는_INVALID_REQUEST(String destination) throws Exception {
        StompTestClient admin = client(jwtProvider.issue(1L, Role.ADMIN, false));

        admin.session().subscribe(destination, new NoopHandler());

        assertThat(admin.awaitError()).isEqualTo("INVALID_REQUEST");
    }

    @Test
    void 클라이언트의_SEND_는_거절되고_다른_구독자에게_전달되지_않는다() throws Exception {
        StompTestClient listener = client(null);
        BlockingQueue<StockMessage> messages = listener.subscribe(STOCK_TOPIC);
        assertReceivesStock(listener, messages);                          // 구독이 등록된 뒤
        StompTestClient sender = client(jwtProvider.issue(7L, Role.CUSTOMER, false));

        sender.session().send(STOCK_TOPIC, "{\"version\":999999,\"changes\":[{\"breadId\":1,\"remaining\":0}]}".getBytes());

        assertThat(sender.awaitError()).isEqualTo("METHOD_NOT_ALLOWED");
        assertThat(messages.poll(500, TimeUnit.MILLISECONDS)).isNull();
    }

    private StompTestClient client(String token) throws Exception {
        StompTestClient client = new StompTestClient(port, token);
        clients.add(client);
        client.session();
        return client;
    }

    private void assertReceivesStock(StompTestClient client) throws Exception {
        assertReceivesStock(client, client.subscribe(STOCK_TOPIC));
    }

    // 구독 등록 전에 보낸 메시지는 받을 수 없으므로 받을 때까지 몇 번 보낸다
    private void assertReceivesStock(StompTestClient client, BlockingQueue<StockMessage> messages) throws Exception {
        StockMessage sent = new StockMessage(1, List.of(new Change(1, 5)));
        StockMessage received = null;
        for (int i = 0; i < 20 && received == null; i++) {
            messagingTemplate.convertAndSend(STOCK_TOPIC, sent);
            received = messages.poll(250, TimeUnit.MILLISECONDS);
        }
        assertThat(received).isEqualTo(sent);
        messages.clear();
        assertThat(client.errors).isEmpty();
    }

    private static class NoopHandler implements org.springframework.messaging.simp.stomp.StompFrameHandler {
        @Override
        public java.lang.reflect.Type getPayloadType(org.springframework.messaging.simp.stomp.StompHeaders headers) {
            return byte[].class;
        }

        @Override
        public void handleFrame(org.springframework.messaging.simp.stomp.StompHeaders headers, Object payload) {
        }
    }
}
