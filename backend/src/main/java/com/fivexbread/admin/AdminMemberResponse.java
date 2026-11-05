package com.fivexbread.admin;

import com.fivexbread.common.MaskedPhone;
import com.fivexbread.member.Member;

/** 사장님 회원 검색 (A-09). 이름 + 번호 뒷 4자리만 (AU-10) */
public record AdminMemberResponse(long memberId, String name, MaskedPhone phone) {

    static AdminMemberResponse from(Member member) {
        return new AdminMemberResponse(member.getId(), member.getName(), member.maskedPhone());
    }
}
