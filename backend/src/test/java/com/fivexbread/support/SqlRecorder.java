package com.fivexbread.support;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.sql.DataSource;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/**
 * 실행한 SQL 문을 기록한다. Hibernate Statistics 는 JdbcTemplate 쿼리를 세지 못해, DataSource 를 감싸 prepareStatement 의 SQL 을 남긴다
 * (NamedParameterJdbcTemplate·Hibernate 는 모두 prepareStatement 를 쓴다).
 * 테스트가 start() 와 stop() 사이에 보낸 요청의 SQL 만 남는다. 다른 스레드(스케줄러 등)의 SQL 이 섞일 수 있으므로
 * 단언은 관심 있는 테이블이 들어간 문장만 골라서 한다 (matching).
 * 쓰는 테스트 클래스만 @Import 한다 (DataSource 를 바꾸므로 별도 컨텍스트가 된다).
 */
@TestConfiguration(proxyBeanMethods = false)
public class SqlRecorder implements BeanPostProcessor {

    private static final List<String> STATEMENTS = new CopyOnWriteArrayList<>();
    private static volatile boolean recording;

    public static void start() {
        STATEMENTS.clear();
        recording = true;
    }

    public static List<String> stop() {
        recording = false;
        return List.copyOf(STATEMENTS);
    }

    /** 기록한 SQL 중 text 를 포함한 것 (대소문자 무시) */
    public static List<String> matching(List<String> statements, String text) {
        return statements.stream().filter(sql -> sql.toLowerCase().contains(text.toLowerCase())).toList();
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (bean instanceof DataSource dataSource && !(bean instanceof Recording)) {
            return new Recording(dataSource);
        }
        return bean;
    }

    private static final class Recording extends DelegatingDataSource {

        Recording(DataSource target) {
            super(target);
        }

        @Override
        public Connection getConnection() throws java.sql.SQLException {
            return wrap(super.getConnection());
        }

        @Override
        public Connection getConnection(String username, String password) throws java.sql.SQLException {
            return wrap(super.getConnection(username, password));
        }

        private static Connection wrap(Connection connection) {
            return (Connection) Proxy.newProxyInstance(SqlRecorder.class.getClassLoader(),
                    new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                        if (recording && method.getName().equals("prepareStatement") && args != null
                                && args[0] instanceof String sql) {
                            STATEMENTS.add(sql);
                        }
                        try {
                            return method.invoke(connection, args);
                        } catch (InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });
        }
    }
}
