package com.fivexbread.common;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * 정의서 10장 "상태 코드와 에러 코드" 표.
 * 400은 요청 형식 오류, 422는 업무 규칙 위반, 409는 서버 자원의 현재 상태와 충돌.
 * 응답의 {@code code}는 상수 이름 그대로, {@code title}은 message.
 * 설정 파일로 바뀌는 숫자(하루 3건, 1건 20개 등)는 문구에 넣지 않고 detail 로 보낸다.
 */
@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "요청 값이 올바르지 않습니다"),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다"),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "번호 또는 비밀번호가 틀렸습니다"),   // AU-05: 가입 여부를 드러내지 않게 문구 하나
    FORBIDDEN(HttpStatus.FORBIDDEN, "접근 권한이 없습니다"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 정보를 찾을 수 없습니다"),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "지원하지 않는 요청 방식입니다"),

    OUT_OF_STOCK(HttpStatus.CONFLICT, "예약 가능한 수량이 부족합니다"),
    PER_USER_LIMIT_EXCEEDED(HttpStatus.CONFLICT, "1인 예약 한도를 초과했습니다"),
    INVALID_STATUS_TRANSITION(HttpStatus.CONFLICT, "현재 상태에서는 변경할 수 없는 예약입니다"),
    IDEMPOTENCY_IN_PROGRESS(HttpStatus.CONFLICT, "같은 요청을 처리하고 있습니다"),
    DUPLICATE_PHONE(HttpStatus.CONFLICT, "이미 가입한 휴대폰 번호입니다"),
    DUPLICATE_BREAD_NAME(HttpStatus.CONFLICT, "이미 같은 이름의 빵이 있습니다"),          // 판매 중인 빵끼리 (A-01)

    PRECONDITION_FAILED(HttpStatus.PRECONDITION_FAILED, "다른 사용자가 먼저 수정했습니다. 새로고침 후 다시 시도해 주세요"),
    PAYLOAD_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE, "파일 크기가 허용 범위를 넘었습니다"),

    RESERVATION_NOT_OPEN(HttpStatus.UNPROCESSABLE_ENTITY, "아직 예약 기간이 아닙니다"),
    RESERVATION_CLOSED(HttpStatus.UNPROCESSABLE_ENTITY, "예약이 마감되었습니다"),
    CANCEL_DEADLINE_PASSED(HttpStatus.UNPROCESSABLE_ENTITY, "취소 가능 시각이 지났습니다"),
    RESERVATION_COUNT_EXCEEDED(HttpStatus.UNPROCESSABLE_ENTITY, "하루 예약 가능 건수를 초과했습니다"),
    TOTAL_QUANTITY_EXCEEDED(HttpStatus.UNPROCESSABLE_ENTITY, "한 번에 예약할 수 있는 수량을 초과했습니다"),
    STOCK_BELOW_RESERVED(HttpStatus.UNPROCESSABLE_ENTITY, "예약 가능 총량은 현재 예약 수량보다 적을 수 없습니다"),
    RESERVATION_BLOCKED(HttpStatus.UNPROCESSABLE_ENTITY, "노쇼로 예약이 제한된 상태입니다"),
    ACTIVE_BREAD_LIMIT_EXCEEDED(HttpStatus.UNPROCESSABLE_ENTITY, "판매 중인 빵은 최대 30종입니다"),   // 부록 B: 고정값
    INVALID_SALE_DATE(HttpStatus.UNPROCESSABLE_ENTITY, "판매일 설정이 규칙에 맞지 않습니다"),          // errors[]: 위반 항목 전부 (A-04)
    SALE_DATE_HOLIDAY(HttpStatus.UNPROCESSABLE_ENTITY, "휴무일에는 수량을 설정할 수 없습니다"),
    INVALID_STOCK_ROWS(HttpStatus.UNPROCESSABLE_ENTITY, "수량 설정에 맞지 않는 행이 있습니다"),        // errors[]: 틀린 행 전부 (A-02)
    INVALID_PICKUP_SLOT(HttpStatus.UNPROCESSABLE_ENTITY, "이 판매일의 픽업 시간대가 아닙니다"),         // C-04, decisions.md 6장
    BREAD_NOT_AVAILABLE(HttpStatus.UNPROCESSABLE_ENTITY, "이 판매일에 예약할 수 없는 빵이 있습니다"),   // errors[]: breadId 전부 (C-04)
    IDEMPOTENCY_KEY_MISMATCH(HttpStatus.UNPROCESSABLE_ENTITY, "같은 Idempotency-Key 로 다른 요청을 보냈습니다"),   // N-06

    ACCOUNT_LOCKED(HttpStatus.LOCKED, "로그인 실패가 반복되어 계정이 잠시 잠겼습니다"),
    IDEMPOTENCY_KEY_REQUIRED(HttpStatus.PRECONDITION_REQUIRED, "Idempotency-Key 헤더가 필요합니다"),
    PRECONDITION_REQUIRED(HttpStatus.PRECONDITION_REQUIRED, "If-Match 헤더가 필요합니다"),
    TOO_MANY_REQUESTS(HttpStatus.TOO_MANY_REQUESTS, "요청이 너무 많습니다. 잠시 후 다시 시도해 주세요"),

    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "일시적인 오류가 발생했습니다"),
    SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "서비스를 일시적으로 이용할 수 없습니다. 잠시 후 다시 시도해 주세요");

    private final HttpStatus status;
    private final String message;

    public String code() {
        return name();
    }
}
