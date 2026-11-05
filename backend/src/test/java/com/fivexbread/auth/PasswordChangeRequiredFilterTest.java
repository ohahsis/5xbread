package com.fivexbread.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fivexbread.common.ApiExceptionHandler;
import com.fivexbread.member.Role;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;

/** pcr 표시가 있는 토큰의 허용 경로 경계 (AU-06, AU-08). 실제 JWT 로 필터 체인 전체를 지난다 */
@WebMvcTest(controllers = PasswordChangeRequiredFilterTest.TestController.class)
@Import({SecurityConfig.class, ApiExceptionHandler.class, JwtTestConfig.class,
        PasswordChangeRequiredFilterTest.TestController.class})
@ActiveProfiles("test")
class PasswordChangeRequiredFilterTest {

    private static final String PASSWORD = "/api/v1/members/me/password";
    private static final String CURRENT = "/api/v1/auth/tokens/current";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JwtProvider jwtProvider;

    @Test
    void pcr이면_비밀번호_변경_재발급_로그아웃은_통과한다() throws Exception {
        perform(put(PASSWORD), Role.CUSTOMER, true).andExpect(status().isOk());
        perform(put(CURRENT), Role.CUSTOMER, true).andExpect(status().isOk());
        perform(delete(CURRENT), Role.CUSTOMER, true).andExpect(status().isOk());
    }

    @Test
    void pcr이면_로그인이_필요한_API는_403_ProblemDetail() throws Exception {
        perform(post("/api/v1/reservations"), Role.CUSTOMER, true)
                .andExpect(status().isForbidden())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("PASSWORD_CHANGE_REQUIRED"))
                .andExpect(jsonPath("$.instance").value("/api/v1/reservations"));
    }

    @Test
    void pcr이면_공개_API도_막는다() throws Exception {
        expectBlocked(get("/api/v1/sale-dates/2026-10-20/breads"));
    }

    @Test
    void 허용_경로라도_메서드가_다르면_막는다() throws Exception {
        expectBlocked(get(PASSWORD));
        expectBlocked(post(CURRENT));
    }

    @Test
    void 허용_경로의_하위_경로와_비슷한_경로는_막는다() throws Exception {
        expectBlocked(put(PASSWORD + "/history"));
        expectBlocked(put("/api/v1/members/me/passwords"));
    }

    @Test
    void pcr_사장님도_관리_API를_쓸_수_없다() throws Exception {
        perform(get("/api/v1/admin/breads"), Role.ADMIN, true)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PASSWORD_CHANGE_REQUIRED"));
    }

    @Test
    void pcr이_없으면_그대로_통과한다() throws Exception {
        perform(post("/api/v1/reservations"), Role.CUSTOMER, false).andExpect(status().isOk());
        perform(get("/api/v1/admin/breads"), Role.ADMIN, false).andExpect(status().isOk());
    }

    @Test
    void 토큰이_없으면_필터가_관여하지_않는다() throws Exception {
        mockMvc.perform(get("/api/v1/sale-dates/2026-10-20/breads")).andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/reservations"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    private void expectBlocked(MockHttpServletRequestBuilder request) throws Exception {
        perform(request, Role.CUSTOMER, true)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PASSWORD_CHANGE_REQUIRED"));
    }

    private ResultActions perform(MockHttpServletRequestBuilder request, Role role, boolean pcr) throws Exception {
        return mockMvc.perform(request.header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtProvider.issue(1L, role, pcr)));
    }

    @RestController
    static class TestController {

        @PutMapping(PASSWORD)
        void password() {
        }

        @PutMapping(CURRENT)
        void reissue() {
        }

        @DeleteMapping(CURRENT)
        void logout() {
        }

        @PostMapping("/api/v1/reservations")
        void reservations() {
        }

        @GetMapping("/api/v1/sale-dates/2026-10-20/breads")
        void breads() {
        }

        @GetMapping("/api/v1/admin/breads")
        void adminBreads() {
        }
    }
}
