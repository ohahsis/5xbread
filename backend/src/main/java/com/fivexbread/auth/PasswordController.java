package com.fivexbread.auth;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** 내 비밀번호 변경 (C-10). 토큰을 새로 내려 주므로 Refresh 쿠키를 만드는 auth 패키지에 둔다 */
@RestController
@RequiredArgsConstructor
public class PasswordController {

    private final AuthFacade authFacade;

    /**
     * 200 새 Access Token(pcr 없음) + 새 Refresh 쿠키. 정의서의 204 와 다르다 (decisions.md 6장).
     * 형식 위반 400, 현재 비밀번호 불일치·번호 포함·현재와 같음 422, 시도 초과 423
     */
    @PutMapping("/api/v1/members/me/password")
    public ResponseEntity<AccessTokenResponse> change(@AuthenticationPrincipal AuthMember member,
                                                      @Valid @RequestBody PasswordChangeRequest request) {
        IssuedTokens tokens = authFacade.changePassword(member.memberId(), request);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE,
                        RefreshTokenCookie.of(tokens.refreshToken(), tokens.refreshTokenTtl()).toString())
                .body(tokens.toResponse());
    }
}
