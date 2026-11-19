package com.fivexbread.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fivexbread.support.IntegrationTestSupport;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Duration;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.actuate.health.HealthEndpointGroups;
import org.springframework.core.env.Environment;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** application.yml 의 설정 값이 실제 빈에 들어갔는지 본다 (decisions.md 6장) */
class ConfigWiringTest extends IntegrationTestSupport {

    @Autowired
    @Qualifier("taskScheduler")
    ThreadPoolTaskScheduler taskScheduler;

    @Autowired
    Environment environment;

    @Autowired
    DataSource dataSource;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    HealthEndpointGroups healthGroups;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Test
    void 스케줄러는_spring_task_scheduling_pool_size_4를_builder로_받는다() {
        // getPoolSize() 는 지금 떠 있는 스레드 수라 설정 값은 core pool size 로 본다
        assertThat(taskScheduler.getScheduledThreadPoolExecutor().getCorePoolSize()).isEqualTo(4);
        assertThat(taskScheduler.getThreadNamePrefix()).isEqualTo("scheduling-");
    }

    @Test
    void 운영_JDBC_URL에_연결_소켓_시간_제한과_락_대기_5초가_있다() {
        // 테스트는 @ServiceConnection 이 컨테이너 URL 로 바꾸므로 application.yml 의 값은 설정으로 본다 (docker 프로필이 쓰는 URL)
        assertThat(environment.getRequiredProperty("spring.datasource.url"))
                .contains("connectTimeout=2000", "socketTimeout=10000", "sessionVariables=innodb_lock_wait_timeout=5");
    }

    @Test
    void local_프로필의_compose_MySQL_라벨에도_같은_시간_제한이_있다() throws Exception {
        // local 은 spring-boot-docker-compose 가 application.yml 대신 compose.yaml 라벨로 URL 을 만든다 (decisions.md 2장 "로컬 인프라")
        String compose = java.nio.file.Files.readString(java.nio.file.Path.of("../compose.yaml"));

        assertThat(compose).contains("org.springframework.boot.jdbc.parameters:")
                .contains("connectTimeout=2000", "socketTimeout=10000", "sessionVariables=innodb_lock_wait_timeout=5");
    }

    @Test
    void Flyway는_socketTimeout_없이_따로_연결한다() {
        // 테스트는 @ServiceConnection 이 Flyway 연결도 컨테이너 URL 로 바꾸므로 설정 값으로 본다 (local·docker 프로필)
        String flywayUrl = environment.getRequiredProperty("spring.flyway.url");

        assertThat(flywayUrl).contains("connectTimeout=2000").doesNotContain("socketTimeout");
    }

    @Test
    void 트랜잭션_기본_시간_제한은_8초다() {
        assertThat(((AbstractPlatformTransactionManager) transactionManager).getDefaultTimeout()).isEqualTo(8);
    }

    @Test
    void 기본_시간_제한을_넘긴_트랜잭션의_쿼리는_8초에서_끊긴다() {
        // socketTimeout(10초)보다 먼저 트랜잭션 시간 제한이 쿼리 시간 제한으로 걸리는지. SLEEP(9) 는 socketTimeout 에는 걸리지 않는다
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        long started = System.nanoTime();

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> jdbcTemplate.queryForObject("SELECT SLEEP(9)", Integer.class)))
                .isInstanceOf(QueryTimeoutException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(8_900));
    }

    @Test
    void 커넥션의_락_대기는_5초이고_Hikari_대기는_10초다() throws Exception {
        // 테스트 컨테이너 URL 에도 같은 값을 넣었으므로(IntegrationTestSupport) 실제 커넥션의 세션 값으로 확인한다
        assertThat(jdbcTemplate.queryForObject("SELECT @@SESSION.innodb_lock_wait_timeout", Integer.class)).isEqualTo(5);
        assertThat(dataSource.unwrap(HikariDataSource.class).getConnectionTimeout()).isEqualTo(10_000);
    }

    @Test
    void readiness_그룹에_db가_들어_있다() {
        var readiness = healthGroups.get("readiness");

        assertThat(readiness).isNotNull();
        assertThat(readiness.isMember("db")).isTrue();
        assertThat(readiness.isMember("readinessState")).isTrue();
        assertThat(readiness.isMember("redis")).isFalse();   // Redis 장애 때는 예약 503·목록 DB 폴백으로 버티므로 빼지 않는다
    }
}
