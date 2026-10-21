package com.fivexbread.member;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fivexbread.support.IntegrationTestSupport;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 사장님 초기 계정 (decisions.md 2장). 설정값을 바꿔 가며 확인하려고 Initializer 를 직접 만든다.
 * 테스트 프로필에는 ADMIN_INITIAL_* 값이 없어 앱 시작 때 실행된 Initializer 는 아무것도 만들지 않는다.
 */
@ExtendWith(OutputCaptureExtension.class)
class AdminAccountInitializerTest extends IntegrationTestSupport {

    private static final String PHONE = "01099990000";
    private static final String PASSWORD = "Init-admin-2026";

    @Autowired
    MemberService memberService;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    StringRedisTemplate redisTemplate;

    @Autowired
    TestRestTemplate restTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM member_agreement");
        jdbcTemplate.update("DELETE FROM member");
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
    }

    @Test
    void ADMIN이_없으면_비밀번호_변경이_필요한_사장님_계정을_만든다() {
        boolean created = initializer("010-9999-0000", PASSWORD).initialize();   // 하이픈은 빼고 저장

        assertThat(created).isTrue();
        Map<String, Object> admin = jdbcTemplate.queryForMap(
                "SELECT phone, password_hash, role, status, must_change_password FROM member WHERE role = 'ADMIN'");
        assertThat(admin.get("phone")).isEqualTo(PHONE);
        assertThat(admin.get("status")).isEqualTo("ACTIVE");
        assertThat(admin.get("must_change_password")).isEqualTo(true);
        String hash = (String) admin.get("password_hash");
        assertThat(hash).isNotEqualTo(PASSWORD).startsWith("$2");
        assertThat(passwordEncoder.matches(PASSWORD, hash)).isTrue();

        // 손님과 같은 로그인 API 로 들어가고, 비밀번호 변경이 필요하다고 알려 준다 (M-01)
        ResponseEntity<JsonNode> login = restTemplate.postForEntity("/api/v1/auth/tokens",
                Map.of("phone", PHONE, "password", PASSWORD), JsonNode.class);
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(login.getBody().get("mustChangePassword").asBoolean()).isTrue();
    }

    @Test
    void ADMIN이_이미_있으면_다시_만들지_않는다() {
        jdbcTemplate.update("INSERT INTO member (phone, password_hash, name, role) VALUES (?, ?, ?, 'ADMIN')",
                "01088887777", passwordEncoder.encode("existing-admin-1"), "기존사장님");

        boolean created = initializer(PHONE, PASSWORD).initialize();

        assertThat(created).isFalse();
        assertThat(adminCount()).isEqualTo(1);
        assertThat(memberCount(PHONE)).isZero();
    }

    @Test
    void 두_번_실행해도_한_명만_생긴다() {
        AdminAccountInitializer initializer = initializer(PHONE, PASSWORD);

        assertThat(initializer.initialize()).isTrue();
        assertThat(initializer.initialize()).isFalse();

        assertThat(adminCount()).isEqualTo(1);
    }

    @Test
    void 앱_3개가_동시에_떠도_한_명만_생기고_중복_키_오류도_나지_않는다(CapturedOutput output) throws Exception {
        int apps = 3;
        ExecutorService executor = Executors.newFixedThreadPool(apps);
        CountDownLatch ready = new CountDownLatch(apps);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < apps; i++) {
            AdminAccountInitializer initializer = initializer(PHONE, PASSWORD);   // 앱마다 따로 뜬 Initializer
            results.add(executor.submit(() -> {
                ready.countDown();
                start.await();
                return initializer.initialize();
            }));
        }
        ready.await();
        start.countDown();

        List<Boolean> created = new ArrayList<>();
        for (Future<Boolean> result : results) {
            created.add(result.get(60, TimeUnit.SECONDS));   // 예외 없이 끝나야 앱이 뜬다
        }
        executor.shutdown();

        assertThat(created).containsOnlyOnce(true);
        assertThat(adminCount()).isEqualTo(1);
        assertThat(output).doesNotContain("Duplicate entry", PHONE);   // 진 앱은 잠금을 기다린 뒤 ADMIN 을 보고 건너뜀
    }

    @Test
    void 값이_비어_있으면_경고만_남기고_건너뛴다(CapturedOutput output) {
        assertThat(initializer(null, null).initialize()).isFalse();
        assertThat(initializer("", PASSWORD).initialize()).isFalse();
        assertThat(initializer(PHONE, "  ").initialize()).isFalse();

        assertThat(adminCount()).isZero();
        assertThat(output).contains("비어 있어 사장님 초기 계정을 만들지 않음");
    }

    @Test
    void 번호_형식이나_비밀번호_규칙이_틀리면_경고만_남기고_건너뛴다(CapturedOutput output) {
        assertThat(initializer("01199990000", PASSWORD).initialize()).isFalse();        // 010 아님
        assertThat(initializer(PHONE, "short1").initialize()).isFalse();                // 8자 미만
        assertThat(initializer(PHONE, "admin99990000").initialize()).isFalse();         // 번호 포함

        assertThat(adminCount()).isZero();
        assertThat(output).contains("010으로 시작하는 11자리가 아니어서", "비밀번호 규칙(AU-03)에 맞지 않아");
    }

    @Test
    void 손님이_같은_번호를_쓰고_있으면_경고만_남기고_건너뛴다(CapturedOutput output) {
        jdbcTemplate.update("INSERT INTO member (phone, password_hash, name) VALUES (?, ?, ?)",
                PHONE, passwordEncoder.encode("customer-pass-1"), "손님");

        assertThat(initializer(PHONE, PASSWORD).initialize()).isFalse();

        assertThat(adminCount()).isZero();
        assertThat(output).contains("같은 번호의 회원이 이미 있어");
    }

    @Test
    void 로그에_번호와_비밀번호_원문을_남기지_않는다(CapturedOutput output) {
        initializer(PHONE, PASSWORD).initialize();                          // 생성
        jdbcTemplate.update("DELETE FROM member");
        jdbcTemplate.update("INSERT INTO member (phone, password_hash, name) VALUES (?, ?, ?)",
                PHONE, passwordEncoder.encode("customer-pass-1"), "손님");
        initializer(PHONE, PASSWORD).initialize();                          // 번호 중복
        initializer("01199990000", PASSWORD).initialize();                  // 형식 오류

        assertThat(output).contains("010-****-0000");
        assertThat(output).doesNotContain(PHONE, "01199990000", PASSWORD);
        assertThat(new AdminAccountProperties(PHONE, PASSWORD).toString()).doesNotContain(PHONE, PASSWORD);
    }

    private AdminAccountInitializer initializer(String phone, String password) {
        return new AdminAccountInitializer(new AdminAccountProperties(phone, password), memberService, passwordEncoder,
                jdbcTemplate);
    }

    private int adminCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM member WHERE role = 'ADMIN'", Integer.class);
    }

    private int memberCount(String phone) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM member WHERE phone = ?", Integer.class, phone);
    }
}
