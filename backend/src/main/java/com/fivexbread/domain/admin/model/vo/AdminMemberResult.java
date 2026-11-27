package com.fivexbread.domain.admin.model.vo;

import com.fivexbread.common.MaskedPhone;
import com.fivexbread.domain.member.model.Member;
import java.time.LocalDate;

/**
 * 사장님이 다룬 회원 결과. 이름 + 마스킹한 번호만 (AU-10)
 *
 * @param reservationBlockedUntil 노쇼 제한 마지막 날(R-17, 그날 포함). 제한이 없으면 null
 */
public record AdminMemberResult(long memberId, String name, MaskedPhone phone, LocalDate reservationBlockedUntil) {

    /** 제한을 지운 뒤. 엔티티는 조건부 UPDATE 전에 읽어 제한 날짜가 남아 있으므로 null 로 둔다 */
    public static AdminMemberResult blockCleared(Member member) {
        return new AdminMemberResult(member.getId(), member.getName(), member.maskedPhone(), null);
    }
}
