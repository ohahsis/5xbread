package com.fivexbread.auth;

import java.time.Duration;
import org.springframework.http.ResponseCookie;

/**
 * Refresh Token 쿠키 (5장 토큰 방식, 13-6).
 * HttpOnly: JS 가 읽지 못해 XSS 로 털리지 않음 / Secure: HTTPS 만(localhost 는 브라우저가 예외로 허용)
 * SameSite=Strict + Path=/api/v1/auth: 다른 사이트에서 온 요청과 인증 API 밖의 요청에는 붙지 않음(CSRF 대비)
 */
final class RefreshTokenCookie {

    static final String NAME = "refresh_token";
    private static final String PATH = "/api/v1/auth";

    private RefreshTokenCookie() {
    }

    static ResponseCookie of(String refreshToken, Duration maxAge) {
        return base(refreshToken).maxAge(maxAge).build();
    }

    /** 로그아웃: 같은 이름·경로로 Max-Age=0 을 보내 브라우저에서 지운다 */
    static ResponseCookie expired() {
        return base("").maxAge(0).build();
    }

    private static ResponseCookie.ResponseCookieBuilder base(String value) {
        return ResponseCookie.from(NAME, value)
                .httpOnly(true)
                .secure(true)
                .sameSite("Strict")
                .path(PATH);
    }
}
