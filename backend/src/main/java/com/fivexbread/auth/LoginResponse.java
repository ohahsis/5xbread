package com.fivexbread.auth;

/**
 * @param expiresIn          Access Token 수명(초)
 * @param mustChangePassword 임시 비밀번호·초기 계정이면 true. 화면이 비밀번호 변경으로 보낸다 (AU-06, AU-08)
 */
public record LoginResponse(String accessToken, long expiresIn, boolean mustChangePassword) {
}
