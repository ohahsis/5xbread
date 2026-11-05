package com.fivexbread.admin;

import com.fivexbread.auth.LoginFailureCounter;
import com.fivexbread.auth.RefreshTokenStore;
import com.fivexbread.common.BusinessException;
import com.fivexbread.common.ErrorCode;
import com.fivexbread.member.Member;
import com.fivexbread.member.MemberService;
import com.fivexbread.member.TemporaryPasswordGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 임시 비밀번호 발급 (A-09, C-09, 13-5 3단계). 순서: 생성 → BCrypt 해시 → DB(비밀번호 + 이력) → 토큰 전부 폐기·잠금 해제(Redis).
 * BCrypt 와 Redis 는 트랜잭션 밖에서 한다. 원문은 돌려주는 값 말고는 어디에도 넘기거나 남기지 않는다.
 * Redis 가 실패하면 503 이고 원문은 사라지지만, 다시 발급하면 비밀번호와 폐기가 함께 다시 처리된다.
 */
@Component
@RequiredArgsConstructor
public class TemporaryPasswordFacade {

    private final MemberService memberService;
    private final TemporaryPasswordService temporaryPasswordService;
    private final PasswordEncoder passwordEncoder;
    private final RefreshTokenStore refreshTokenStore;
    private final LoginFailureCounter loginFailureCounter;

    public TemporaryPasswordResponse issue(long adminId, long memberId) {
        Member member = memberService.findById(memberId)
                .filter(Member::canReceiveTemporaryPassword)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));

        String temporaryPassword = TemporaryPasswordGenerator.generate(member.getPhone());
        temporaryPasswordService.save(adminId, memberId, passwordEncoder.encode(temporaryPassword));

        refreshTokenStore.revokeAll(memberId);
        loginFailureCounter.reset(member.getPhone());
        return new TemporaryPasswordResponse(temporaryPassword);
    }
}
