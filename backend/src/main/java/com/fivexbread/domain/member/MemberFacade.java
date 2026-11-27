package com.fivexbread.domain.member;

import com.fivexbread.domain.member.model.Member;
import com.fivexbread.domain.member.model.vo.SignupCommand;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 가입 순서: Redis 가입 횟수 → 재가입 제한(AU-07) → BCrypt 해시 → DB 트랜잭션.
 * Redis 호출과 BCrypt(수십 ms의 CPU 작업)는 DB 커넥션을 잡기 전에 끝낸다.
 */
@Component
@RequiredArgsConstructor
public class MemberFacade {

    private final SignupRateLimiter signupRateLimiter;
    private final PasswordEncoder passwordEncoder;
    private final MemberService memberService;
    private final WithdrawnPhoneHasher withdrawnPhoneHasher;

    public Member signUp(SignupCommand command, String clientIp) {
        signupRateLimiter.acquire(clientIp);
        memberService.checkRejoin(withdrawnPhoneHasher.hash(command.phone()));
        String passwordHash = passwordEncoder.encode(command.password());
        return memberService.signUp(command.phone(), passwordHash, command.name());
    }
}
