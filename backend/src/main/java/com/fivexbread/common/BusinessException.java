package com.fivexbread.common;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Getter;
import org.springframework.http.HttpHeaders;

/**
 * 모든 업무 예외. ApiExceptionHandler가 ProblemDetail로 바꾼다.
 * detail은 응답의 detail(없으면 생략), properties는 응답 최상위 필드로 그대로 들어간다.
 * 예: OUT_OF_STOCK의 errors[], RESERVATION_BLOCKED의 blockedUntil
 * headers는 응답 헤더로 들어간다. 예: 423·429의 Retry-After
 */
@Getter
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;
    private final String detail;
    private final Map<String, Object> properties;
    private final HttpHeaders headers;

    public BusinessException(ErrorCode errorCode) {
        this(errorCode, null, Map.of());
    }

    public BusinessException(ErrorCode errorCode, String detail) {
        this(errorCode, detail, Map.of());
    }

    public BusinessException(ErrorCode errorCode, String detail, Map<String, Object> properties) {
        this(errorCode, detail, properties, HttpHeaders.EMPTY);
    }

    public BusinessException(ErrorCode errorCode, String detail, Map<String, Object> properties, HttpHeaders headers) {
        super(detail != null ? detail : errorCode.getMessage());
        this.errorCode = errorCode;
        this.detail = detail;
        // 값이 null인 항목도 응답에 null로 내보내야 해서 Map.copyOf 대신 사용
        this.properties = Collections.unmodifiableMap(new LinkedHashMap<>(properties));
        this.headers = HttpHeaders.readOnlyHttpHeaders(headers);
    }

    public static BusinessException withRetryAfter(ErrorCode errorCode, long retryAfterSeconds) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds));
        return new BusinessException(errorCode, null, Map.of(), headers);
    }
}
