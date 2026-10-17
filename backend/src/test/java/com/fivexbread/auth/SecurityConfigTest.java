package com.fivexbread.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fivexbread.common.ApiExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;

@WebMvcTest(controllers = SecurityConfigTest.TestController.class)
@Import({SecurityConfig.class, ApiExceptionHandler.class, SecurityConfigTest.TestController.class})
class SecurityConfigTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    void 가입_로그인_재발급_로그아웃은_토큰_없이_CSRF_토큰_없이도_호출된다() throws Exception {
        mockMvc.perform(post("/api/v1/members")).andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/auth/tokens")).andExpect(status().isOk());
        mockMvc.perform(put("/api/v1/auth/tokens/current")).andExpect(status().isOk());
        mockMvc.perform(delete("/api/v1/auth/tokens/current")).andExpect(status().isOk());
    }

    @Test
    void 판매_정보_조회는_공개다() throws Exception {
        mockMvc.perform(get("/api/v1/sale-dates/2026-10-20/breads")).andExpect(status().isOk());
    }

    @Test
    void 인증이_필요한_API에_토큰이_없으면_401_ProblemDetail() throws Exception {
        mockMvc.perform(get("/api/v1/members/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.instance").value("/api/v1/members/me"));
    }

    @Test
    void 관리_API는_익명이면_401() throws Exception {
        mockMvc.perform(get("/api/v1/admin/breads"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    @WithMockUser(roles = "CUSTOMER")
    void 관리_API는_손님이면_403_ProblemDetail() throws Exception {
        mockMvc.perform(get("/api/v1/admin/breads"))
                .andExpect(status().isForbidden())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void 관리_API는_사장님이면_통과() throws Exception {
        mockMvc.perform(get("/api/v1/admin/breads")).andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "CUSTOMER")
    void 세션을_만들지_않는다() throws Exception {
        mockMvc.perform(get("/api/v1/members/me"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Set-Cookie"));
    }

    @RestController
    static class TestController {

        @PostMapping("/api/v1/members")
        void signUp() {
        }

        @PostMapping("/api/v1/auth/tokens")
        void login() {
        }

        @PutMapping("/api/v1/auth/tokens/current")
        void refresh() {
        }

        @DeleteMapping("/api/v1/auth/tokens/current")
        void logout() {
        }

        @GetMapping("/api/v1/sale-dates/{date}/breads")
        void breads() {
        }

        @GetMapping("/api/v1/members/me")
        void me() {
        }

        @GetMapping("/api/v1/admin/breads")
        void adminBreads() {
        }
    }
}
