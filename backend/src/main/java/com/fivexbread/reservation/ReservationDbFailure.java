package com.fivexbread.reservation;

import com.fivexbread.common.BusinessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionTimedOutException;

/**
 * 예약 DB 트랜잭션(ReservationTxService.create)이 실패했을 때 커밋됐을 수 있는지 (decisions.md 6장 "예약 DB 실패 뒤 보상 판단").
 * ReservationFacade 는 이 분류 하나로 "바로 보상"과 "커밋 여부를 읽고 판단"을 고른다. catch 순서에 기대지 않는다.
 *
 * <pre>
 * NO_CONNECTION  커넥션을 못 얻음   CannotCreateTransactionException(Hikari 대기 초과·연결 거부), CannotGetJdbcConnectionException
 *                                  → 문장을 하나도 보내지 못했다. 바로 보상
 * ROLLED_BACK    롤백 확실         업무 판단(BusinessException·StockShortageException), UNIQUE 위반, 락 실패(1213·1205),
 *                                  TransactionTimedOutException(시간 제한을 넘긴 뒤 다음 문장 앞에서 난다 = 커밋 전)
 *                                  → 바로 보상
 * UNKNOWN        결과 모름         그 밖(쿼리 시간 초과, 연결 끊김, 커밋 실패 등). 커밋 응답 전에 끊겼을 수 있다
 *                                  → 같은 키의 예약을 DB 에서 읽어 보고 판단
 * </pre>
 */
enum ReservationDbFailure {
    NO_CONNECTION,
    ROLLED_BACK,
    UNKNOWN;

    static ReservationDbFailure classify(RuntimeException e) {
        // CannotGetJdbcConnectionException 은 DataAccessResourceFailureException 의 하위 클래스다.
        // 상위 클래스(연결 끊김 = UNKNOWN)로 분류하지 않도록 정확한 타입을 본다
        if (e instanceof CannotCreateTransactionException || e instanceof CannotGetJdbcConnectionException) {
            return NO_CONNECTION;
        }
        if (e instanceof BusinessException
                || e instanceof StockShortageException
                || e instanceof DataIntegrityViolationException
                || e instanceof PessimisticLockingFailureException
                || e instanceof TransactionTimedOutException) {
            return ROLLED_BACK;
        }
        return UNKNOWN;
    }
}
