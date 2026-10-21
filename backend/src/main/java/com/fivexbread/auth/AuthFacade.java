package com.fivexbread.auth;

import com.fivexbread.common.BusinessException;
import com.fivexbread.common.ErrorCode;
import com.fivexbread.member.Member;
import com.fivexbread.member.MemberService;
import com.fivexbread.member.MemberStatus;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 로그인 순서 (13-6 1단계): 잠금 확인·시도 횟수 증가(Redis, 원자적) → 회원 조회(DB, 읽기 트랜잭션) → BCrypt 비교
 * → 성공이면 횟수·잠금 삭제(Redis) → Access Token(JWT) + Refresh Token(Redis) 발급.
 * Redis 호출과 BCrypt 는 트랜잭션 밖에서 한다.
 */
@Component
public class AuthFacade {

    private final LoginFailureCounter loginFailureCounter;
    private final MemberService memberService;
    private final PasswordEncoder passwordEncoder;
    private final JwtProvider jwtProvider;
    private final JwtProperties jwtProperties;
    private final RefreshTokenStore refreshTokenStore;

    /** 없는 번호일 때 비교할 해시. 같은 인코더(cost 10)로 만들어 실제 비교와 걸리는 시간이 같다 */
    private final String dummyHash;

    public AuthFacade(LoginFailureCounter loginFailureCounter, MemberService memberService,
                      PasswordEncoder passwordEncoder, JwtProvider jwtProvider, JwtProperties jwtProperties,
                      RefreshTokenStore refreshTokenStore) {
        this.loginFailureCounter = loginFailureCounter;
        this.memberService = memberService;
        this.passwordEncoder = passwordEncoder;
        this.jwtProvider = jwtProvider;
        this.jwtProperties = jwtProperties;
        this.refreshTokenStore = refreshTokenStore;
        this.dummyHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    public IssuedTokens login(LoginRequest request) {
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
        return issueTokens(member);
    }

    /**
     * Refresh Token 회전 (13-6 3단계). GETDEL 로 꺼내며 지우고 새 쌍을 발급한다. 이미 쓴 토큰이면 그 회원의 토큰 전부 폐기 → 401.
     * 토큰은 14일 동안 살아 있어 그 사이 권한·비밀번호 변경 필요 여부·상태가 바뀔 수 있으므로 회원을 DB 에서 다시 읽는다.
     */
    public IssuedTokens reissue(String refreshToken) {
        long memberId = refreshTokenStore.consume(refreshToken);    // 정상 1회 시도에 대해 memberId를 반환하거나, 토큰 재사용에 대해 해당 회원의 토큰을 전부 폐기한다.
        Member member = memberService.findById(memberId)
                .filter(m -> m.getStatus() == MemberStatus.ACTIVE)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHENTICATED));
        return issueTokens(member);
    }

    /** 로그아웃 (13-6 4단계). 이 Refresh Token 만 삭제. 없는 토큰이어도 예외 없이 끝낸다 (컨트롤러가 204) */
    public void logout(String refreshToken) {
        refreshTokenStore.delete(refreshToken);
    }

    // 로그인·재발급 공통: Access Token(JWT) + Refresh Token(Redis 저장) 새 쌍
    private IssuedTokens issueTokens(Member member) {
        String accessToken = jwtProvider.issue(member.getId(), member.getRole(), member.isMustChangePassword());
        String refreshToken = refreshTokenStore.issue(member.getId());
        return new IssuedTokens(accessToken, jwtProperties.accessTokenTtl().toSeconds(),
                member.isMustChangePassword(), refreshToken, jwtProperties.refreshTokenTtl());
    }
}
