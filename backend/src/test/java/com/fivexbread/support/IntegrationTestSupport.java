package com.fivexbread.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;

/**
 * MySQL·Redis 컨테이너를 테스트 JVM 하나에서 한 번만 띄워 모든 통합 테스트가 같이 쓴다.
 * MySQL은 compose.yaml과 같은 문자셋·타임존(+09:00)으로 띄운다 (decisions.md 3장).
 * 연결 수 상한은 기본 151 에서 올린다. 설정(@Import·@MockitoBean)이 다른 테스트마다 스프링 컨텍스트가 따로 캐시되고
 * 각자 Hikari 풀(10개)을 쥐고 있어, 컨텍스트가 15개를 넘으면 새 컨텍스트가 Flyway 연결부터 얻지 못한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class IntegrationTestSupport {

    @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withCommand("--character-set-server=utf8mb4",
                    "--collation-server=utf8mb4_0900_ai_ci",
                    "--default-time-zone=+09:00",
                    "--max-connections=500")
            // @ServiceConnection 은 application.yml 의 URL 대신 컨테이너 URL 을 쓰므로 시간 제한·락 대기를 여기에도 같게 둔다
            .withUrlParam("connectTimeout", "2000")
            .withUrlParam("socketTimeout", "10000")
            .withUrlParam("sessionVariables", "innodb_lock_wait_timeout=5");

    // compose 두 개와 같은 Redis 설정: 비밀번호와 noeviction (decisions.md 2장 "Redis 비밀번호").
    // @ServiceConnection 은 GenericContainer 의 비밀번호를 넘기지 못해(호스트·포트만) 아래 @DynamicPropertySource 로 넣는다
    static final String REDIS_PASSWORD = "test-only-redis-password";

    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7")
            .withCommand("redis-server", "--requirepass", REDIS_PASSWORD, "--maxmemory-policy", "noeviction")
            .withExposedPorts(6379);

    static {
        MYSQL.start();
        REDIS.start();
    }

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> REDIS_PASSWORD);
    }

    /**
     * Redis 컨테이너를 멈춘다(docker pause). 연결은 남고 응답만 오지 않아 명령이 시간 제한(2초)에 걸린다.
     * 다른 테스트가 같은 컨테이너를 쓰므로 반드시 finally 에서 resumeRedis 를 부른다.
     */
    protected static void pauseRedis() {
        REDIS.getDockerClient().pauseContainerCmd(REDIS.getContainerId()).exec();
    }

    protected static void resumeRedis() {
        REDIS.getDockerClient().unpauseContainerCmd(REDIS.getContainerId()).exec();
    }
}
