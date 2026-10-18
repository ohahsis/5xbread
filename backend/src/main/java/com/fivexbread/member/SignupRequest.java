package com.fivexbread.member;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** POST /api/v1/members (5장 인증 API, 9장 필드 규격 "회원") */
@PasswordExcludesPhone
public record SignupRequest(
        @NotBlank @Phone String phone,
        @NotBlank @Password String password,
        @NotBlank
        @Size(min = 2, max = 20, message = NAME_MESSAGE)
        @Pattern(regexp = "^[가-힣A-Za-z]+( [가-힣A-Za-z]+)*$", message = NAME_MESSAGE)   // 한글 완성형·영문·공백, 연속 공백 불가
        String name,
        @NotNull @Valid Agreements agreements
) {

    static final String NAME_MESSAGE = "이름은 한글 또는 영문 2~20자로 입력해 주세요";

    public SignupRequest {
        phone = PhoneValidator.normalize(phone);           // 저장은 숫자 11자리
        name = name == null ? null : name.strip();         // 앞뒤 공백은 제거한 뒤 셈
    }

    public record Agreements(
            @AssertTrue(message = "필수 약관에 동의해 주세요") boolean terms,
            @AssertTrue(message = "필수 약관에 동의해 주세요") boolean privacy
    ) {
    }
}
