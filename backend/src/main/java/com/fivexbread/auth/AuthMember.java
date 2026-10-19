package com.fivexbread.auth;

import com.fivexbread.member.Role;

/**
 * SecurityContext 에 들어가는 로그인 회원. 컨트롤러에서 @AuthenticationPrincipal 로 받는다.
 * 토큰에 담긴 값만 들고 있다 (요청마다 DB 조회 없음, 정의서 13-6).
 *
 * @param passwordChangeRequired 임시 비밀번호·초기 계정이라 비밀번호 변경이 필요한지 (JWT pcr 클레임)
 */
public record AuthMember(long memberId, Role role, boolean passwordChangeRequired) {
}
