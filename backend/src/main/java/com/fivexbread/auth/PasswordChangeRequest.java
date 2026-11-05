package com.fivexbread.auth;

import com.fivexbread.member.Password;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * PUT /api/v1/members/me/password (5장 인증 API).
 * 현재 비밀번호는 규칙(@Password)을 다시 검사하지 않는다(LoginRequest 와 같은 이유). 임시 비밀번호도 여기로 온다.
 * 번호 뒷 8자리 포함과 현재와 같음은 저장된 값을 봐야 해서 AuthFacade 가 422 로 검사한다.
 */
public record PasswordChangeRequest(
        @NotBlank @Size(max = 64) String currentPassword,   // 가입 규칙 상한. 그보다 긴 값은 BCrypt 까지 가지 않음
        @NotBlank @Password String newPassword
) {

    // record 기본 toString 은 모든 값을 출력한다. 로그·예외 메시지에 비밀번호 원문이 남지 않게 가린다 (AU-10)
    @Override
    public String toString() {
        return "PasswordChangeRequest[***]";
    }
}
