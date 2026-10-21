package com.fivexbread.member;

import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 가입 순서: Redis 가입 횟수 → BCrypt 해시 → DB 트랜잭션.
 * Redis 호출과 BCrypt(수십 ms의 CPU 작업)는 DB 커넥션을 잡기 전에 끝낸다.
 */
@Component
@RequiredArgsConstructor
public class MemberFacade {

    private final SignupRateLimiter signupRateLimiter;
    private final PasswordEncoder passwordEncoder;
    private final MemberService memberService;

    public SignupResponse signUp(SignupRequest request, String clientIp) {
        signupRateLimiter.acquire(clientIp);
        String passwordHash = passwordEncoder.encode(request.password());
        Member member = memberService.signUp(request.phone(), passwordHash, request.name());
        return SignupResponse.from(member);
    }
}
