package com.fivexbread.member;

import io.swagger.v3.oas.annotations.media.Schema;
import com.fivexbread.common.MaskedPhone;
import java.time.LocalDate;

/**
 * 내 정보 (U-10 마이페이지, C-11 예약 제한 안내). 이름은 본인이 고치는 값이라 원문, 번호는 마스킹 (AU-10)
 *
 * @param reservationBlockedUntil 노쇼 예약 제한 마지막 날(R-17, 그날 포함). 제한이 없으면 null
 */
public record MyInfoResponse(long memberId, String name, MaskedPhone phone, Role role,
                             @Schema(nullable = true) LocalDate reservationBlockedUntil) {

    static MyInfoResponse from(Member member) {
        return new MyInfoResponse(member.getId(), member.getName(), member.maskedPhone(), member.getRole(),
                member.getReservationBlockedUntil());
    }
}
