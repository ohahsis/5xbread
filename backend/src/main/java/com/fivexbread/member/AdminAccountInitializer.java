package com.fivexbread.member;

import com.fivexbread.common.BusinessException;
import com.fivexbread.common.MaskedPhone;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 앱 시작 때 ADMIN 회원이 하나도 없으면 사장님 초기 계정을 만든다 (decisions.md 2장, AU-08).
 * 값이 비었거나 형식이 틀려도 앱은 띄우고 경고만 남긴다. 로그에는 번호를 마스킹해서만, 비밀번호는 남기지 않는다.
 *
 * 앱 3개가 동시에 떠도 한 명만 생기도록 MySQL 이름 잠금(GET_LOCK)으로 확인·저장을 한 앱씩 한다.
 * uk_member_phone 에만 맡기면 진 쪽 INSERT 의 중복 키 오류를 Hibernate 가 번호 원문과 함께 ERROR 로그에 남긴다.
 * 잠금은 저장 트랜잭션이 커밋된 뒤 풀어서, 다음 앱은 만들어진 ADMIN 을 보고 건너뛴다.
 * BCrypt(수십 ms)는 ADMIN 이 이미 있으면 하지 않고, 하더라도 잠금·트랜잭션 밖에서 한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminAccountInitializer implements ApplicationRunner {

    private static final String LOCK_NAME = "fivexbread:initial-admin";
    private static final int LOCK_TIMEOUT_SECONDS = 10;

    private final AdminAccountProperties properties;
    private final MemberService memberService;
    private final PasswordEncoder passwordEncoder;
    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(ApplicationArguments args) {
        initialize();
    }

    /** @return 이번 실행에서 계정을 만들었는지 */
    boolean initialize() {
        if (memberService.existsAdmin()) {
            return false;
        }
        String phone = PhoneValidator.normalize(properties.initialPhone());
        String password = properties.initialPassword();
        // 다음 경우들에 대해 경고 로그만 남기고, 앱은 정상적으로 뜬다.
        if (phone == null || phone.isBlank() || password == null || password.isBlank()) {
            log.warn("ADMIN 계정이 없지만 ADMIN_INITIAL_PHONE·ADMIN_INITIAL_PASSWORD 가 비어 있어 사장님 초기 계정을 만들지 않음");
            return false;
        }
        if (!PhoneValidator.isValid(phone)) {
            log.warn("ADMIN_INITIAL_PHONE 이 010으로 시작하는 11자리가 아니어서 사장님 초기 계정을 만들지 않음");
            return false;
        }
        if (!PasswordRules.meetsBasicRule(password) || PasswordRules.hasRepeatedChars(password)
                || PasswordRules.containsPhone(password, phone)) {
            log.warn("ADMIN_INITIAL_PASSWORD 가 비밀번호 규칙(AU-03)에 맞지 않아 사장님 초기 계정을 만들지 않음");
            return false;
        }

        String passwordHash = passwordEncoder.encode(password);

        // 왜 JPA 가 아니라 이렇게 복잡하게 하나? -> 이 잠금은 연결(세션)에 묶이기 때문이다.
        // 잠근 연결과 같은 연결에서 풀어야 하는데, JPA 나 일반 jdbcTemplate.query 는 실행할 때마다 연결 풀에서 아무 연결이나 빌렸다가 바로 돌려준다.
        // 잠그는 연결과 푸는 연결이 달라질 수 있어서 Connection 을 직접 사용하여 묶어준다.
        return Boolean.TRUE.equals(jdbcTemplate.execute((ConnectionCallback<Boolean>) connection -> {
            if (!acquireLock(connection)) {         // 1. 해당 앱을 락으로 잠그는 시도를 하고
                log.warn("다른 앱이 사장님 초기 계정을 만드는 중이라 {}초 기다린 뒤 건너뜀", LOCK_TIMEOUT_SECONDS);   // 해당 앱이 락을 얻지 못한 경우
                return false;
            }
            try {
                return create(phone, passwordHash); // 2. (저장은 다른 연결의 트랜잭션에서, 커밋까지 끝남)
            } finally {
                releaseLock(connection);            // 3. 같은 연결로 푼다.
            }
        }));
    }

    // 저장은 잠금을 잡은 연결과 다른 연결의 트랜잭션에서 하고, 커밋까지 끝난 뒤 돌아온다
    private boolean create(String phone, String passwordHash) {
        try {
            boolean created = memberService.createInitialAdmin(phone, passwordHash);
            if (created) {
                log.info("사장님 초기 계정 생성 phone={} (첫 로그인 때 비밀번호 변경 필요)", MaskedPhone.from(phone));
            }
            return created;
        } catch (BusinessException | DataIntegrityViolationException e) {
            // 손님이 이미 그 번호로 가입한 경우 (DataIntegrityViolation 은 확인과 저장 사이에 가입한 드문 경우)
            log.warn("같은 번호의 회원이 이미 있어 사장님 초기 계정을 만들지 않음 phone={}", MaskedPhone.from(phone));
            return false;
        }
    }

    // GET_LOCK 은 1: 획득, 0: 시간 초과로 획득하지 못함, NULL: 오류. 잠금은 연결(세션)에 묶이므로 같은 연결에서 푼다
    private static boolean acquireLock(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT GET_LOCK(?, ?)")) {  // prepareStatement 는 실행할 SQL 문장, ? 은 나중에 값을 채울 자리
            // PreparedStatement 의 번호는 1부터 센다. 문자열을 직접 이어 붙이지 않고 ?를 쓰는 건 SQL 인젝션을 막는 기본 방법이다.
            statement.setString(1, LOCK_NAME);          // 첫 번째 ? 에 "fivexbread:initial-admin"
            statement.setInt(2, LOCK_TIMEOUT_SECONDS);  // 두 번째 ? 에 10
            try (ResultSet resultSet = statement.executeQuery()) {  // SQL 을 실행하고 결과 표(ResultSet)를 받는다. 결과는 한 행, 한 칸 짜리 표(값은 1, 0, Null 중 하나)
                // "행이 있고, 값이 1이면" true = 잠금 획득
                return resultSet.next() && resultSet.getInt(1) == 1;    // resultSet.next(): 커서를 첫 행으로 옮기고, 행이 있으면 true / resultSet.getInt(1): 그 행의 첫 번째 칸 값
            }
        }
    }

    private static void releaseLock(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT RELEASE_LOCK(?)")) {
            statement.setString(1, LOCK_NAME);
            statement.execute();
        }
    }
}
