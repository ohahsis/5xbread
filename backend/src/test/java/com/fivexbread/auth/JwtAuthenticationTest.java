package com.fivexbread.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fivexbread.common.ApiExceptionHandler;
import com.fivexbread.common.ClockConfig;
import com.fivexbread.member.Role;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 실제 JWT 로 필터 → SecurityContext → 인가 → 401·403 ProblemDetail 까지 확인한다 (정의서 13-6 2단계).
 */
@WebMvcTest(controllers = JwtAuthenticationTest.TestController.class)
@Import({SecurityConfig.class, ApiExceptionHandler.class, JwtTestConfig.class, JwtAuthenticationTest.TestController.class})
@ActiveProfiles("test")
class JwtAuthenticationTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JwtProvider jwtProvider;

    @Autowired
    JwtProperties jwtProperties;

    @Autowired
    Clock clock;

    @Test
    void 정상_토큰이면_회원ID_권한이_SecurityContext에_들어간다() throws Exception {
        String token = jwtProvider.issue(7L, Role.CUSTOMER, true);

        withBearer(token, "/api/v1/members/me")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.memberId").value(7))
                .andExpect(jsonPath("$.role").value("CUSTOMER"))
                .andExpect(jsonPath("$.passwordChangeRequired").value(true))
                .andExpect(jsonPath("$.authorities[0]").value("ROLE_CUSTOMER"));
    }

    @Test
    void Bearer_대소문자는_구분하지_않는다() throws Exception {
        String token = jwtProvider.issue(7L, Role.CUSTOMER, false);

        mockMvc.perform(get("/api/v1/members/me").header(HttpHeaders.AUTHORIZATION, "bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void 토큰이_없으면_401_ProblemDetail() throws Exception {
        expectUnauthorized(mockMvc.perform(get("/api/v1/members/me")));
    }

    @Test
    void 만료된_토큰은_401_ProblemDetail() throws Exception {
        String expired = issuedMinutesAgo(31);

        expectUnauthorized(withBearer(expired, "/api/v1/members/me"));
    }

    @Test
    void 서명이_위조된_토큰은_401_ProblemDetail() throws Exception {
        String forged = new JwtProvider(
                new JwtProperties("attacker-secret-0123456789abcdef0123456789", Duration.ofMinutes(30)),
                clock).issue(7L, Role.ADMIN, false);

        expectUnauthorized(withBearer(forged, "/api/v1/members/me"));
        expectUnauthorized(withBearer(forged, "/api/v1/admin/breads"));   // 위조한 ADMIN 권한으로 403도 아닌 401
    }

    @Test
    void 형식이_잘못된_토큰은_401_ProblemDetail() throws Exception {
        expectUnauthorized(withBearer("not-a-jwt", "/api/v1/members/me"));
        expectUnauthorized(withBearer("", "/api/v1/members/me"));
    }

    @Test
    void Bearer가_아닌_인증_방식은_무시하고_401() throws Exception {
        String token = jwtProvider.issue(7L, Role.CUSTOMER, false);

        expectUnauthorized(mockMvc.perform(get("/api/v1/members/me")
                .header(HttpHeaders.AUTHORIZATION, "Basic " + token)));
    }

    @Test
    void 손님_토큰으로_관리_API에_접근하면_403_ProblemDetail() throws Exception {
        String token = jwtProvider.issue(7L, Role.CUSTOMER, false);

        withBearer(token, "/api/v1/admin/breads")
                .andExpect(status().isForbidden())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.instance").value("/api/v1/admin/breads"));
    }

    @Test
    void 사장님_토큰이면_관리_API_통과() throws Exception {
        String token = jwtProvider.issue(1L, Role.ADMIN, false);

        withBearer(token, "/api/v1/admin/breads").andExpect(status().isOk());
    }

    @Test
    void 공개_경로는_만료된_토큰이_붙어_와도_통과한다() throws Exception {
        String expired = issuedMinutesAgo(31);

        mockMvc.perform(put("/api/v1/auth/tokens/current").header(HttpHeaders.AUTHORIZATION, "Bearer " + expired))
                .andExpect(status().isOk());
    }

    private String issuedMinutesAgo(long minutes) {
        Instant issuedAt = clock.instant().minus(Duration.ofMinutes(minutes));
        return new JwtProvider(jwtProperties, Clock.fixed(issuedAt, ClockConfig.KST)).issue(7L, Role.CUSTOMER, false);
    }

    private ResultActions withBearer(String token, String uri) throws Exception {
        return mockMvc.perform(get(uri).header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    private static void expectUnauthorized(ResultActions result) throws Exception {
        result.andExpect(status().isUnauthorized())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @RestController
    static class TestController {

        @GetMapping("/api/v1/members/me")
        MeResponse me(@AuthenticationPrincipal AuthMember member,
                      Authentication authentication) {
            var authorities = authentication.getAuthorities().stream().map(Object::toString).toList();
            return new MeResponse(member.memberId(), member.role(), member.passwordChangeRequired(), authorities);
        }

        @GetMapping("/api/v1/admin/breads")
        void adminBreads() {
        }

        @PutMapping("/api/v1/auth/tokens/current")
        void refresh() {
        }
    }

    record MeResponse(long memberId, Role role, boolean passwordChangeRequired, List<String> authorities) {
    }
}
