package com.fivexbread.reservation;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.springframework.stereotype.Component;

/**
 * DB 커넥션 풀(Hikari)에서 커넥션을 기다리는 스레드가 있는지. ReservationFacade 가 "결과 모름" 뒤 커밋 확인 조회를 할지 고를 때 본다.
 * 기다리는 스레드가 있으면 조회도 풀에서 최대 Hikari connection-timeout(10초)을 더 기다리므로 조회하지 않는다
 * (decisions.md 6장 "예약 DB 실패 뒤 보상 판단").
 */
@Component
public class ConnectionPoolStatus {

    private final DataSource dataSource;

    public ConnectionPoolStatus(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** 풀을 알 수 없으면(Hikari 가 아님, 아직 풀이 안 만들어짐) false: 지금처럼 조회한다 */
    public boolean hasThreadsAwaitingConnection() {
        HikariPoolMXBean pool = pool();
        return pool != null && pool.getThreadsAwaitingConnection() > 0;
    }

    private HikariPoolMXBean pool() {
        try {
            if (!dataSource.isWrapperFor(HikariDataSource.class)) {
                return null;
            }
            return dataSource.unwrap(HikariDataSource.class).getHikariPoolMXBean();
        } catch (SQLException e) {
            return null;
        }
    }
}
