package com.fivexbread.auth;

import com.fivexbread.auth.InvalidTokenException.Reason;
import com.fivexbread.member.Role;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.SecurityException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Component;

/**
 * Access Token(JWT, HS256) 발급·검증 (정의서 5장, 13-6).
 * 클레임은 회원 ID(sub)·권한(role)·비밀번호 변경 필요(pcr)·발급/만료 시각만 넣는다. JWT 는 서명만 되고 암호화되지 않는다.
 */
@Component
public class JwtProvider {

    private static final String ROLE = "role";
    private static final String PASSWORD_CHANGE_REQUIRED = "pcr";

    private final SecretKey key;
    private final JwtProperties properties;
    private final Clock clock;
    private final JwtParser parser;

    public JwtProvider(JwtProperties properties, Clock clock) {
        // 32바이트(256비트) 미만이면 WeakKeyException 으로 앱이 뜨지 않는다
        this.key = Keys.hmacShaKeyFor(properties.secret().getBytes(StandardCharsets.UTF_8));
        this.properties = properties;
        this.clock = clock;
        this.parser = Jwts.parser()
                .verifyWith(key)
                .clock(() -> Date.from(clock.instant()))   // 만료 판정도 주입된 Clock 기준
                .build();
    }

    public String issue(long memberId, Role role, boolean passwordChangeRequired) {
        Instant now = clock.instant();
        return Jwts.builder()
                .subject(String.valueOf(memberId))
                .claim(ROLE, role.name())
                .claim(PASSWORD_CHANGE_REQUIRED, passwordChangeRequired)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(properties.accessTokenTtl())))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    /**
     * 서명·만료를 확인하고 클레임을 꺼낸다. 저장소 조회는 하지 않는다.
     *
     * @throws InvalidTokenException 만료·서명 불일치·형식 오류(서명 없는 토큰, 클레임 누락 포함)
     */
    public AuthMember parse(String token) {
        try {
            Claims claims = parser.parseSignedClaims(token).getPayload();
            String subject = claims.getSubject();
            String role = claims.get(ROLE, String.class);
            Boolean passwordChangeRequired = claims.get(PASSWORD_CHANGE_REQUIRED, Boolean.class);
            if (subject == null || role == null || passwordChangeRequired == null) {
                throw new InvalidTokenException(Reason.MALFORMED, null);
            }
            return new AuthMember(Long.parseLong(subject), Role.valueOf(role), passwordChangeRequired);
        } catch (ExpiredJwtException e) {
            throw new InvalidTokenException(Reason.EXPIRED, e);
        } catch (SecurityException e) {                  // SignatureException 포함
            throw new InvalidTokenException(Reason.INVALID_SIGNATURE, e);
        } catch (JwtException | IllegalArgumentException e) {   // 형식 오류, 숫자가 아닌 sub, 없는 role
            throw new InvalidTokenException(Reason.MALFORMED, e);
        }
    }
}
