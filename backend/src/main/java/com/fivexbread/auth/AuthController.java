package com.fivexbread.auth;

import com.fivexbread.common.BusinessException;
import com.fivexbread.common.ErrorCode;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth/tokens")
@RequiredArgsConstructor
public class AuthController {

    private final AuthFacade authFacade;

    /** 로그인 = 토큰 자원 생성 (5장 인증 API). 201 Access Token + Refresh 쿠키. 401 번호·비밀번호 불일치, 423 잠김 */
    @PostMapping
    public ResponseEntity<AccessTokenResponse> login(@Valid @RequestBody LoginRequest request) {
        IssuedTokens tokens = authFacade.login(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .header(HttpHeaders.SET_COOKIE, refreshCookie(tokens))
                .body(tokens.toResponse());
    }

    /**
     * 재발급 = 현재 토큰 자원 교체 (5장 인증 API). 요청 본문 없이 Refresh 쿠키로만 인증한다.
     * 200 새 Access Token + 새 Refresh 쿠키. 쿠키가 없거나 만료·재사용이면 401
     */
    @PutMapping("/current")
    public ResponseEntity<AccessTokenResponse> reissue(
            @CookieValue(name = RefreshTokenCookie.NAME, required = false) String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        }
        IssuedTokens tokens = authFacade.reissue(refreshToken);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshCookie(tokens))
                .body(tokens.toResponse());
    }

    /**
     * 로그아웃 = 현재 토큰 자원 삭제 (5장 인증 API). 204, 쿠키 만료.
     * 쿠키가 없거나 이미 지워진 토큰이어도 결과(로그아웃된 상태)가 같으므로 204 (멱등)
     */
    @DeleteMapping("/current")
    public ResponseEntity<Void> logout(
            @CookieValue(name = RefreshTokenCookie.NAME, required = false) String refreshToken) {
        if (refreshToken != null && !refreshToken.isBlank()) {
            authFacade.logout(refreshToken);
        }
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, RefreshTokenCookie.expired().toString())
                .build();
    }

    private static String refreshCookie(IssuedTokens tokens) {
        return RefreshTokenCookie.of(tokens.refreshToken(), tokens.refreshTokenTtl()).toString();
    }
}
