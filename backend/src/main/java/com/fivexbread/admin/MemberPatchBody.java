package com.fivexbread.admin;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.time.LocalDate;

/** 회원 PATCH 본문의 OpenAPI 문서용 모양 (A-10). 실제로는 Map 으로 받아 MemberPatch 가 검사한다 */
@Schema(name = "AdminMemberPatch", description = "노쇼 예약 제한 해제. 이 필드만 받는다")
record MemberPatchBody(
        @Schema(requiredMode = RequiredMode.REQUIRED, nullable = true, description = "null 만 허용(해제)")
        LocalDate reservationBlockedUntil) {
}
