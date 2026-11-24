package com.fivexbread.inbound.controller.member.model.response;

import com.fivexbread.domain.member.model.Member;
import com.fivexbread.common.MaskedPhone;

public record SignupResponse(Long memberId, MaskedPhone phone, String name) {

    public static SignupResponse from(Member member) {
        return new SignupResponse(member.getId(), member.maskedPhone(), member.getName());
    }
}
