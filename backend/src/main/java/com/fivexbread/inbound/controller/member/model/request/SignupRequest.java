package com.fivexbread.inbound.controller.member.model.request;

import com.fivexbread.domain.member.MemberName;
import com.fivexbread.domain.member.Password;
import com.fivexbread.domain.member.Phone;
import com.fivexbread.domain.member.PhoneValidator;
import com.fivexbread.domain.member.model.vo.SignupCommand;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** POST /api/v1/members (5장 인증 API, 9장 필드 규격 "회원") */
@PasswordExcludesPhone
public record SignupRequest(
        @NotBlank @Phone String phone,
        @NotBlank @Password String password,
        @NotBlank @MemberName String name,
        @NotNull @Valid Agreements agreements
) {

    public SignupRequest {
        phone = PhoneValidator.normalize(phone);           // 저장은 숫자 11자리
        name = name == null ? null : name.strip();         // 앞뒤 공백은 제거한 뒤 셈 (이름 수정도 같다)
    }

    public record Agreements(
            @AssertTrue(message = "필수 약관에 동의해 주세요") boolean terms,
            @AssertTrue(message = "필수 약관에 동의해 주세요") boolean privacy
    ) {
    }

    public SignupCommand toCommand() {
        return new SignupCommand(phone, password, name);
    }
}
