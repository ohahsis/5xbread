package com.fivexbread.inbound.controller.admin.model.response;

import io.swagger.v3.oas.annotations.media.Schema;
import com.fivexbread.common.MaskedPhone;
import com.fivexbread.domain.member.model.Member;
import java.time.LocalDate;

/**
 * 사장님 회원 검색 (A-09)·제한 해제 (A-10). 이름 + 번호 뒷 4자리만 (AU-10)
 *
 * @param reservationBlockedUntil 노쇼 제한 마지막 날(R-17, 그날 포함). 제한이 없으면 null
 */
public record AdminMemberResponse(long memberId, String name, MaskedPhone phone,
                                  @Schema(nullable = true) LocalDate reservationBlockedUntil) {

    public static AdminMemberResponse from(Member member) {
        return new AdminMemberResponse(member.getId(), member.getName(), member.maskedPhone(),
                member.getReservationBlockedUntil());
    }

    public AdminMemberResponse withoutBlock() {
        return new AdminMemberResponse(memberId, name, phone, null);
    }
}
