package com.fivexbread.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fivexbread.auth.InvalidTokenException.Reason;
import com.fivexbread.common.ClockConfig;
import com.fivexbread.member.Role;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.WeakKeyException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.Test;

class JwtProviderTest {

    private static final String SECRET = "test-only-jwt-secret-0123456789abcdef0123456789";
    private static final Instant NOW = Instant.parse("2026-10-20T00:00:00Z");
    private static final JwtProperties PROPERTIES = new JwtProperties(SECRET, Duration.ofMinutes(30));

    private final JwtProvider provider = providerAt(NOW);

    @Test
    void 발급한_토큰에서_회원ID_권한_비밀번호변경필요를_꺼낸다() {
        String token = provider.issue(42L, Role.ADMIN, true);

        assertThat(provider.parse(token)).isEqualTo(new AuthMember(42L, Role.ADMIN, true));
    }

    @Test
    void 만료는_발급_30분_뒤다() {
        String token = provider.issue(1L, Role.CUSTOMER, false);

        var claims = Jwts.parser().verifyWith(key(SECRET)).clock(() -> Date.from(NOW)).build()
                .parseSignedClaims(token).getPayload();
        assertThat(claims.getIssuedAt().toInstant()).isEqualTo(NOW);
        assertThat(claims.getExpiration().toInstant()).isEqualTo(NOW.plus(Duration.ofMinutes(30)));
        assertThat(providerAt(NOW.plus(Duration.ofMinutes(29))).parse(token).memberId()).isEqualTo(1L);
    }

    @Test
    void 만료된_토큰은_EXPIRED() {
        String token = provider.issue(1L, Role.CUSTOMER, false);

        assertReason(providerAt(NOW.plus(Duration.ofMinutes(30)).plusSeconds(1)), token, Reason.EXPIRED);
    }

    @Test
    void 다른_키로_서명한_토큰은_INVALID_SIGNATURE() {
        String forged = new JwtProvider(
                new JwtProperties("attacker-secret-0123456789abcdef0123456789", Duration.ofMinutes(30)),
                Clock.fixed(NOW, ClockConfig.KST)).issue(1L, Role.ADMIN, false);

        assertReason(provider, forged, Reason.INVALID_SIGNATURE);
    }

    @Test
    void 내용을_바꾸고_서명은_그대로_둔_토큰은_INVALID_SIGNATURE() {
        String[] parts = provider.issue(1L, Role.CUSTOMER, false).split("\\.");
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8)
                .replace("CUSTOMER", "ADMIN");
        String tampered = parts[0] + "."
                + Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8))
                + "." + parts[2];

        assertReason(provider, tampered, Reason.INVALID_SIGNATURE);
    }

    @Test
    void 형식이_잘못된_토큰은_MALFORMED() {
        assertReason(provider, "not-a-jwt", Reason.MALFORMED);
        assertReason(provider, "aaa.bbb.ccc", Reason.MALFORMED);
        assertReason(provider, "", Reason.MALFORMED);
    }

    @Test
    void 서명_없는_토큰_alg_none은_MALFORMED() {
        String unsigned = Jwts.builder().subject("1").claim("role", "ADMIN").claim("pcr", false)
                .expiration(Date.from(NOW.plusSeconds(60))).compact();

        assertReason(provider, unsigned, Reason.MALFORMED);
    }

    @Test
    void 서명은_맞지만_클레임이_빠지거나_잘못되면_MALFORMED() {
        var expiration = Date.from(NOW.plusSeconds(60));
        String noRole = Jwts.builder().subject("1").claim("pcr", false).expiration(expiration)
                .signWith(key(SECRET)).compact();
        String unknownRole = Jwts.builder().subject("1").claim("role", "OWNER").claim("pcr", false)
                .expiration(expiration).signWith(key(SECRET)).compact();
        String textSubject = Jwts.builder().subject("abc").claim("role", "ADMIN").claim("pcr", false)
                .expiration(expiration).signWith(key(SECRET)).compact();

        assertReason(provider, noRole, Reason.MALFORMED);
        assertReason(provider, unknownRole, Reason.MALFORMED);
        assertReason(provider, textSubject, Reason.MALFORMED);
    }

    @Test
    void 비밀키가_32바이트보다_짧으면_만들_수_없다() {
        assertThatThrownBy(() -> new JwtProvider(new JwtProperties("short-secret", Duration.ofMinutes(30)),
                Clock.fixed(NOW, ClockConfig.KST)))
                .isInstanceOf(WeakKeyException.class);
    }

    private static JwtProvider providerAt(Instant instant) {
        return new JwtProvider(PROPERTIES, Clock.fixed(instant, ClockConfig.KST));
    }

    private static SecretKey key(String secret) {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    private static void assertReason(JwtProvider provider, String token, Reason reason) {
        assertThatThrownBy(() -> provider.parse(token))
                .isInstanceOfSatisfying(InvalidTokenException.class, e -> assertThat(e.getReason()).isEqualTo(reason));
    }
}
