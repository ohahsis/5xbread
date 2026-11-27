package com.fivexbread.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.fivexbread.domain.admin.model.enums.AdminAction;
import com.fivexbread.domain.admin.model.AdminActionLog;
import com.fivexbread.domain.admin.repository.AdminActionLogRepository;
import com.fivexbread.support.IntegrationTestSupport;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.TimeZone;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 시간대 설정 일치 가드 (decisions.md 3장 "시간대 설정 일치"). 아래 넷 중 하나라도 어긋나면 LocalDateTime 이 몇 시간 밀려 저장된다.
 * JVM 기본 시간대(main·Dockerfile·Gradle user.timezone), hibernate.jdbc.time_zone, JDBC URL serverTimezone, MySQL default-time-zone.
 * JPA 와 JdbcTemplate 은 같은 드라이버를 써서 둘 다 같은 방향으로 밀리면 서로 비교해도 맞아 보이므로,
 * 저장된 값은 DATE_FORMAT 문자열(드라이버 변환 없음)로도 확인한다.
 * 확인: Gradle 의 user.timezone 을 빼고 TZ=UTC 로 돌리면 이 테스트가 실패한다(PR 기록).
 */
class TimeZoneGuardTest extends IntegrationTestSupport {

    private static final LocalDateTime SAMPLE = LocalDateTime.of(2026, 10, 10, 9, 15, 30, 123_000_000);
    private static final String SAMPLE_TEXT = "2026-10-10 09:15:30.123";
    private static final String STORED_TEXT = "DATE_FORMAT(created_at, '%Y-%m-%d %H:%i:%s.%f')";
    private static final long MARKER_ADMIN = 987_654_321L;          // 이 테스트가 만든 행만 지우려는 표시

    @Autowired
    AdminActionLogRepository repository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    Clock clock;

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM admin_action_log WHERE admin_id = ?", MARKER_ADMIN);
    }

    @Test
    void JVM_기본_시간대와_주입한_Clock은_KST() {
        assertThat(TimeZone.getDefault().getID()).isEqualTo("Asia/Seoul");
        assertThat(clock.getZone()).isEqualTo(Times.KST);
    }

    @Test
    void JPA로_쓴_LocalDateTime을_JdbcTemplate으로_읽어도_같고_저장된_값도_그_시각이다() {
        long id = repository.saveAndFlush(AdminActionLog.onSaleDate(MARKER_ADMIN, AdminAction.STOCK_RECONCILED,
                LocalDate.of(2026, 10, 10), null, SAMPLE)).getId();

        assertThat(jdbcTemplate.queryForObject("SELECT created_at FROM admin_action_log WHERE id = ?",
                LocalDateTime.class, id)).isEqualTo(SAMPLE);
        assertThat(stored(id)).isEqualTo(SAMPLE_TEXT);
    }

    @Test
    void JdbcTemplate으로_쓴_값을_JPA로_읽어도_같다() {
        jdbcTemplate.update("INSERT INTO admin_action_log (admin_id, action, target_type, target_id, created_at) "
                + "VALUES (?, 'STOCK_RECONCILED', 'SALE_DATE', '2026-10-10', ?)", MARKER_ADMIN, SAMPLE);
        jdbcTemplate.update("INSERT INTO admin_action_log (admin_id, action, target_type, target_id, created_at) "
                + "VALUES (?, 'STOCK_RECONCILED', 'SALE_DATE', '2026-10-11', '" + SAMPLE_TEXT + "')", MARKER_ADMIN);

        var rows = jdbcTemplate.queryForList("SELECT id, target_id FROM admin_action_log WHERE admin_id = ?",
                MARKER_ADMIN);
        assertThat(rows).hasSize(2);
        for (var row : rows) {
            long id = ((Number) row.get("id")).longValue();
            assertThat(repository.findById(id).orElseThrow().getCreatedAt()).as(row.get("target_id").toString())
                    .isEqualTo(SAMPLE);
            assertThat(stored(id)).isEqualTo(SAMPLE_TEXT);
        }
    }

    @Test
    void DB_현재_시각은_앱의_KST_현재_시각과_1분_이내() {
        String dbNow = jdbcTemplate.queryForObject("SELECT DATE_FORMAT(CURRENT_TIMESTAMP(3), '%Y-%m-%d %H:%i:%s')",
                String.class);
        LocalDateTime appNow = LocalDateTime.now(clock);

        LocalDateTime parsed = LocalDateTime.parse(dbNow, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        assertThat(Duration.between(parsed, appNow).abs()).isLessThan(Duration.ofMinutes(1));
    }

    // 드라이버 변환을 거치지 않은 저장 값 (마이크로초 6자리 중 앞 3자리)
    private String stored(long id) {
        String text = jdbcTemplate.queryForObject("SELECT " + STORED_TEXT + " FROM admin_action_log WHERE id = ?",
                String.class, id);
        return text.substring(0, SAMPLE_TEXT.length());
    }
}
