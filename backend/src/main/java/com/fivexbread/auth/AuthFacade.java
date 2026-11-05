package com.fivexbread.auth;

import com.fivexbread.auth.RefreshTokenStore.RotatedToken;
import com.fivexbread.common.BusinessException;
import com.fivexbread.common.ErrorCode;
import com.fivexbread.member.Member;
import com.fivexbread.member.MemberService;
import com.fivexbread.member.MemberStatus;
import com.fivexbread.member.PasswordRules;
import com.fivexbread.member.Role;
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
        return tokens(member.getId(), member.getRole(), member.isMustChangePassword(),
                refreshTokenStore.issue(member.getId()));
    }

    /**
     * Refresh Token 회전 (13-6 3단계). 꺼내며 지우기와 새 토큰 저장을 Redis 에서 한 번에 한다. 이미 쓴 토큰이면 그 회원의 토큰 전부 폐기 → 401.
     * 토큰은 14일 동안 살아 있어 그 사이 권한·비밀번호 변경 필요 여부·상태가 바뀔 수 있으므로 회원을 DB 에서 다시 읽는다.
     */
    public IssuedTokens reissue(String refreshToken) {
        RotatedToken rotated = refreshTokenStore.rotate(refreshToken);  // 재사용이면 해당 회원의 토큰을 전부 폐기하고 401
        Member member = memberService.findById(rotated.memberId())
                .filter(m -> m.getStatus() == MemberStatus.ACTIVE)
                .orElse(null);
        if (member == null) {
            refreshTokenStore.delete(rotated.refreshToken());
            throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        }
        return tokens(member.getId(), member.getRole(), member.isMustChangePassword(), rotated.refreshToken());
    }

    /** 로그아웃 (13-6 4단계). 이 Refresh Token 만 삭제. 없는 토큰이어도 예외 없이 끝낸다 (컨트롤러가 204) */
    public void logout(String refreshToken) {
        refreshTokenStore.delete(refreshToken);
    }

    /**
     * 비밀번호 변경 (C-10, 13-5 2단계). 순서: 시도 횟수(Redis) → BCrypt 비교 → 새 비밀번호 규칙 → BCrypt 해시
     * → 해시 조건부 UPDATE(DB) → 토큰 전부 폐기(Redis) → 새 쌍 발급.
     * 현재 비밀번호 대입은 로그인과 같은 횟수·잠금으로 막는다. 따로 세면 Access Token 을 가진 사람이 잠금을 우회한다.
     *
     * 쿠키 Path 가 /api/v1/auth 라 이 요청에는 Refresh 쿠키가 오지 않아 "지금 기기"의 토큰을 알 수 없다.
     * 그래서 전부 폐기한 뒤 지금 기기에 새 쌍을 내려 준다. 브라우저는 같은 이름·경로의 쿠키를 덮어써 로그인이 유지되고,
     * 새 Access Token 에는 pcr 표시가 없다 (decisions.md 6장).
     *
     * @throws BusinessException 현재 비밀번호 불일치 422, 잠김 423, 번호 포함·현재와 같음 422
     */
    public IssuedTokens changePassword(long memberId, PasswordChangeRequest request) {
        Member member = memberService.findById(memberId)
                .filter(m -> m.getStatus() == MemberStatus.ACTIVE)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHENTICATED));
        verifyCurrentPassword(member, request.currentPassword());

        String newPassword = request.newPassword();
        if (PasswordRules.containsPhone(newPassword, member.getPhone())) {
            throw new BusinessException(ErrorCode.PASSWORD_CONTAINS_PHONE);
        }
        if (newPassword.equals(request.currentPassword())) {     // 현재 비밀번호는 위에서 확인했으니 BCrypt 를 또 돌리지 않는다
            throw new BusinessException(ErrorCode.PASSWORD_SAME_AS_CURRENT);
        }

        String newHash = passwordEncoder.encode(newPassword);
        if (!memberService.changePassword(memberId, member.getPasswordHash(), newHash)) {
            throw new BusinessException(ErrorCode.INVALID_CURRENT_PASSWORD);   // 확인한 비밀번호가 그사이 바뀌었다
        }
        refreshTokenStore.revokeAll(memberId);
        return tokens(memberId, member.getRole(), false, refreshTokenStore.issue(memberId));
    }

    private void verifyCurrentPassword(Member member, String currentPassword) {
        String phone = member.getPhone();
        boolean lastAttempt = loginFailureCounter.acquire(phone);
        if (!passwordEncoder.matches(currentPassword, member.getPasswordHash())) {
            loginFailureCounter.recordFailure(phone, lastAttempt);   // 잠금을 만든 시도면 423
            throw new BusinessException(ErrorCode.INVALID_CURRENT_PASSWORD);
        }
        loginFailureCounter.recordSuccess(phone);
    }

    // 로그인·재발급·비밀번호 변경 공통: Access Token(JWT) + 이미 저장한 Refresh Token
    private IssuedTokens tokens(long memberId, Role role, boolean mustChangePassword, String refreshToken) {
        String accessToken = jwtProvider.issue(memberId, role, mustChangePassword);
        return new IssuedTokens(accessToken, jwtProperties.accessTokenTtl().toSeconds(),
                mustChangePassword, refreshToken, jwtProperties.refreshTokenTtl());
    }
}
