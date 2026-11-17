package com.fivexbread.auth;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 내 비밀번호 변경 (C-10)과 탈퇴 (AU-07). 둘 다 비밀번호를 다시 확인하고 Refresh 쿠키·토큰을 다루므로 auth 패키지에 둔다.
 * 내 정보 조회·이름 수정은 member.MemberController.
 */
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

    /**
     * 탈퇴 (AU-07). 204 + Refresh 쿠키 만료. 비밀번호 불일치 422, 시도 초과 423, 진행 중인 예약(RESERVED) 409, 사장님 403.
     * 쿠키 Path 가 /api/v1/auth 라 이 요청에는 쿠키가 오지 않지만, 만료 Set-Cookie 는 경로와 상관없이 브라우저의 쿠키를 지운다.
     */
    @DeleteMapping("/api/v1/members/me")
    public ResponseEntity<Void> withdraw(@AuthenticationPrincipal AuthMember member,
                                         @Valid @RequestBody WithdrawalRequest request) {
        authFacade.withdraw(member.memberId(), request.password());
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, RefreshTokenCookie.expired().toString())
                .build();
    }
}
