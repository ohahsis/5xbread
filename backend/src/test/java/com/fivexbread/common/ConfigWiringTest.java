package com.fivexbread.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.fivexbread.support.IntegrationTestSupport;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.actuate.health.HealthEndpointGroups;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
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

    @Test
    void 스케줄러는_spring_task_scheduling_pool_size_4를_builder로_받는다() {
        // getPoolSize() 는 지금 떠 있는 스레드 수라 설정 값은 core pool size 로 본다
        assertThat(taskScheduler.getScheduledThreadPoolExecutor().getCorePoolSize()).isEqualTo(4);
        assertThat(taskScheduler.getThreadNamePrefix()).isEqualTo("scheduling-");
    }

    @Test
    void 운영_JDBC_URL에_연결_소켓_시간_제한과_락_대기_5초가_있다() {
        // 테스트는 @ServiceConnection 이 컨테이너 URL 로 바꾸므로 application.yml 의 값은 설정으로 본다 (local·docker 프로필이 쓰는 URL)
        assertThat(environment.getRequiredProperty("spring.datasource.url"))
                .contains("connectTimeout=2000", "socketTimeout=10000", "sessionVariables=innodb_lock_wait_timeout=5");
    }

    @Test
    void 커넥션의_락_대기는_5초이고_Hikari_대기는_3초다() throws Exception {
        // 테스트 컨테이너 URL 에도 같은 값을 넣었으므로(IntegrationTestSupport) 실제 커넥션의 세션 값으로 확인한다
        assertThat(jdbcTemplate.queryForObject("SELECT @@SESSION.innodb_lock_wait_timeout", Integer.class)).isEqualTo(5);
        assertThat(dataSource.unwrap(HikariDataSource.class).getConnectionTimeout()).isEqualTo(3000);
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
