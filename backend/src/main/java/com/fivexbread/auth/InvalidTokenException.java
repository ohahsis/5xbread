package com.fivexbread.auth;

import lombok.Getter;

/**
 * Access Token 검증 실패. 원인은 로그로만 구분하고 응답은 모두 401 하나다 (정의서 13-6).
 */
@Getter
public class InvalidTokenException extends RuntimeException {

    public enum Reason { EXPIRED, INVALID_SIGNATURE, MALFORMED }

    private final Reason reason;

    public InvalidTokenException(Reason reason, Throwable cause) {
        super(reason.name(), cause);
        this.reason = reason;
    }
}
