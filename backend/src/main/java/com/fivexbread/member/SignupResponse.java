package com.fivexbread.member;

import com.fivexbread.common.MaskedPhone;

public record SignupResponse(Long memberId, MaskedPhone phone, String name) {

    static SignupResponse from(Member member) {
        return new SignupResponse(member.getId(), member.maskedPhone(), member.getName());
    }
}
