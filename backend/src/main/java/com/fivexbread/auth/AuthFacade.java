package com.fivexbread.auth;

import com.fivexbread.common.BusinessException;
import com.fivexbread.common.ErrorCode;
import com.fivexbread.member.Member;
import com.fivexbread.member.MemberService;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 로그인 순서 (13-6 1단계): 잠금 확인·시도 횟수 증가(Redis, 원자적) → 회원 조회(DB, 읽기 트랜잭션) → BCrypt 비교
 * → 성공이면 횟수·잠금 삭제(Redis) → JWT 발급.
 * Redis 호출과 BCrypt 는 트랜잭션 밖에서 한다. Refresh Token 발급은 아직 없다.
 */
@Component
public class AuthFacade {

    private final LoginFailureCounter loginFailureCounter;
    private final MemberService memberService;
    private final PasswordEncoder passwordEncoder;
    private final JwtProvider jwtProvider;
    private final JwtProperties jwtProperties;

    /** 없는 번호일 때 비교할 해시. 같은 인코더(cost 10)로 만들어 실제 비교와 걸리는 시간이 같다 */
    private final String dummyHash;

    public AuthFacade(LoginFailureCounter loginFailureCounter, MemberService memberService,
                      PasswordEncoder passwordEncoder, JwtProvider jwtProvider, JwtProperties jwtProperties) {
        this.loginFailureCounter = loginFailureCounter;
        this.memberService = memberService;
        this.passwordEncoder = passwordEncoder;
        this.jwtProvider = jwtProvider;
        this.jwtProperties = jwtProperties;
        this.dummyHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    public LoginResponse login(LoginRequest request) {
        String phone = request.phone();
        // 잠겨 있으면 비밀번호가 맞아도 423. 없는 번호도 세야 잠금 여부로 가입 여부를 알 수 없다
        boolean lastAttempt = loginFailureCounter.acquire(phone);

        Optional<Member> found = memberService.findByPhone(phone);
        // 없는 번호도 BCrypt 비교를 한 번 해서 응답 시간으로 가입 여부를 알 수 없게 한다
        String hash = found.map(Member::getPasswordHash).orElse(dummyHash);
        boolean matches = passwordEncoder.matches(request.password(), hash);
        if (found.isEmpty() || !matches) {
            loginFailureCounter.recordFailure(phone, lastAttempt);   // 잠금을 만든 시도면 423
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
        }

        loginFailureCounter.recordSuccess(phone);
        Member member = found.get();
        String accessToken = jwtProvider.issue(member.getId(), member.getRole(), member.isMustChangePassword());
        return new LoginResponse(accessToken, jwtProperties.accessTokenTtl().toSeconds(), member.isMustChangePassword());
    }
}
