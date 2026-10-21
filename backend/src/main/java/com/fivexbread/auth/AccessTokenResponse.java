package com.fivexbread.auth;

/**
 * 로그인(201)·재발급(200) 응답 본문. Refresh Token 은 본문이 아니라 HttpOnly 쿠키로만 내려간다.
 *
 * @param expiresIn          Access Token 수명(초)
 * @param mustChangePassword 임시 비밀번호·초기 계정이면 true. 화면이 비밀번호 변경으로 보낸다 (AU-06, AU-08)
 */
public record AccessTokenResponse(String accessToken, long expiresIn, boolean mustChangePassword) {
}
