package com.fivexbread.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * DELETE /api/v1/members/me 본문 (AU-07). 비밀번호 재확인. 규칙(@Password)은 다시 검사하지 않는다(LoginRequest 와 같은 이유).
 * DELETE 에 본문을 싣는 것은 HTTP 에서 허용되고 Nginx 도 그대로 넘긴다.
 */
public record WithdrawalRequest(@NotBlank @Size(max = 64) String password) {

    // 로그·예외 메시지에 비밀번호 원문이 남지 않게 가린다 (AU-10)
    @Override
    public String toString() {
        return "WithdrawalRequest[***]";
    }
}
