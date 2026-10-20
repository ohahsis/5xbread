package com.fivexbread.auth;

import com.fivexbread.member.Phone;
import com.fivexbread.member.PhoneValidator;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * POST /api/v1/auth/tokens (5장 인증 API).
 * 비밀번호는 가입 규칙(@Password)을 다시 검사하지 않는다. 규칙이 바뀌어도 기존 비밀번호로 로그인할 수 있어야 한다.
 * 형식이 틀린 번호는 400으로 먼저 걸러 실패 카운터 키가 생기지 않게 한다.
 */
public record LoginRequest(
        @NotBlank @Phone String phone,
        @NotBlank @Size(max = 64) String password      // 가입 규칙 상한. 그보다 긴 값은 BCrypt 까지 가지 않음
) {

    public LoginRequest {
        phone = PhoneValidator.normalize(phone);         // 저장된 값과 같은 숫자 11자리로 비교·카운트
    }
}
