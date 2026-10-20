package com.fivexbread.auth;

import java.time.Duration;

/**
 * Facade 가 새로 발급한 토큰 쌍을 컨트롤러로 넘기는 값. 컨트롤러가 본문(AccessTokenResponse)과 Refresh 쿠키로 나눠 내려준다.
 *
 * @param refreshToken    쿠키에 담을 원문. Redis 에는 해시만 저장한다 (13-6)
 * @param refreshTokenTtl 쿠키 Max-Age. Redis 키 TTL 과 같은 값 (5장: 14일)
 */
public record IssuedTokens(
        String accessToken,
        long expiresIn,
        boolean mustChangePassword,
        String refreshToken,
        Duration refreshTokenTtl
) {

    public AccessTokenResponse toResponse() {
        return new AccessTokenResponse(accessToken, expiresIn, mustChangePassword);
    }

    // record 기본 toString 은 모든 값을 출력한다. 로그·예외 메시지에 토큰 원문이 남지 않게 가린다
    @Override
    public String toString() {
        return "IssuedTokens[expiresIn=%d, mustChangePassword=%s, refreshTokenTtl=%s]"
                .formatted(expiresIn, mustChangePassword, refreshTokenTtl);
    }
}
