package com.fivexbread.reservation;

import static org.assertj.core.api.Assertions.assertThat;

import com.fivexbread.common.BusinessException;
import com.fivexbread.common.ErrorCode;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.orm.jpa.JpaSystemException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.transaction.annotation.Transactional;

/** 예약 DB 실패 분류 (decisions.md 6장 "예약 DB 실패 뒤 보상 판단"의 표) */
class ReservationDbFailureTest {

    static List<Object[]> cases() {
        return List.of(
                // 커넥션을 못 얻음
                row(new CannotCreateTransactionException("Hikari 대기 초과"), ReservationDbFailure.NO_CONNECTION),
                row(new CannotGetJdbcConnectionException("연결 거부"), ReservationDbFailure.NO_CONNECTION),
                // 롤백 확실
                row(new BusinessException(ErrorCode.UNAUTHENTICATED), ReservationDbFailure.ROLLED_BACK),
                row(new StockShortageException(List.of(3L)), ReservationDbFailure.ROLLED_BACK),
                row(new DataIntegrityViolationException("uk_reservation_idem"), ReservationDbFailure.ROLLED_BACK),
                row(new DuplicateKeyException("uk_reservation_no"), ReservationDbFailure.ROLLED_BACK),
                row(new CannotAcquireLockException("1205"), ReservationDbFailure.ROLLED_BACK),
                row(new DeadlockLoserDataAccessException("1213", null), ReservationDbFailure.ROLLED_BACK),
                row(new TransactionTimedOutException("시간 제한 초과"), ReservationDbFailure.ROLLED_BACK),
                // 결과 모름
                row(new QueryTimeoutException("소켓 시간 초과"), ReservationDbFailure.UNKNOWN),
                row(new DataAccessResourceFailureException("연결 끊김"), ReservationDbFailure.UNKNOWN),
                row(new TransientDataAccessResourceException("일시 장애"), ReservationDbFailure.UNKNOWN),
                row(new TransactionSystemException("커밋 실패"), ReservationDbFailure.UNKNOWN),
                row(new JpaSystemException(new RuntimeException("Hibernate")), ReservationDbFailure.UNKNOWN),
                row(new IllegalStateException("알 수 없음"), ReservationDbFailure.UNKNOWN));
    }

    @ParameterizedTest(name = "{0} → {1}")
    @MethodSource("cases")
    void 예외를_세_가지로_나눈다(RuntimeException e, ReservationDbFailure expected) {
        assertThat(ReservationDbFailure.classify(e)).isEqualTo(expected);
    }

    @Test
    void 커밋_여부_확인_조회는_읽기_전용이고_시간_제한_2초다() throws NoSuchMethodException {
        Transactional tx = ReservationQueryService.class.getMethod("findCreated", long.class, String.class)
                .getAnnotation(Transactional.class);

        assertThat(tx.readOnly()).isTrue();
        assertThat(tx.timeout()).isEqualTo(2);
    }

    private static Object[] row(RuntimeException e, ReservationDbFailure expected) {
        return new Object[] {e, expected};
    }
}
