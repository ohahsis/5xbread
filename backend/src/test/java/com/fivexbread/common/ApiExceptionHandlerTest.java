package com.fivexbread.common;

import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@WebMvcTest(controllers = ApiExceptionHandlerTest.TestController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({ApiExceptionHandlerTest.TestController.class, ApiExceptionHandler.class})
@ActiveProfiles("test")   // 기본 프로필(local)이면 .env 를 읽고 SQL 바인딩 값을 TRACE 로 남겨 다른 테스트 로그에도 섞인다
class ApiExceptionHandlerTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    void 업무_예외는_ErrorCode의_상태와_code_추가_필드로_응답한다() throws Exception {
        mockMvc.perform(get("/test/out-of-stock"))
                .andExpect(status().isConflict())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://bakery.example/problems/out-of-stock"))
                .andExpect(jsonPath("$.title").value("예약 가능한 수량이 부족합니다"))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.code").value("OUT_OF_STOCK"))
                .andExpect(jsonPath("$.instance").value("/test/out-of-stock"))
                .andExpect(jsonPath("$.traceId").value(matchesPattern("[0-9a-f]{16}")))
                .andExpect(jsonPath("$.errors[0].breadId").value(22))
                .andExpect(jsonPath("$.errors[0].remaining").value(0));
    }

    @Test
    void 업무_예외의_detail과_null_값_필드를_그대로_내보낸다() throws Exception {
        mockMvc.perform(get("/test/blocked"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("RESERVATION_BLOCKED"))
                .andExpect(jsonPath("$.detail").value("2026-10-29까지 예약할 수 없습니다"))
                .andExpect(jsonPath("$.blockedUntil").value("2026-10-29"))
                .andExpect(jsonPath("$.note").isEmpty());
    }

    @Test
    void 검증_실패는_400과_필드별_errors를_돌려준다() throws Exception {
        mockMvc.perform(post("/test/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\" \",\"quantity\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.errors.length()").value(2))
                .andExpect(jsonPath("$.errors[?(@.field == 'name')].code").value("NotBlank"))
                .andExpect(jsonPath("$.errors[?(@.field == 'quantity')].message").value("1 이상이어야 합니다"));
    }

    @Test
    void 처리하지_못한_예외는_500과_traceId만_내보낸다() throws Exception {
        mockMvc.perform(get("/test/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.traceId").exists())
                .andExpect(jsonPath("$.detail").doesNotExist())
                .andExpect(content().string(not(containsString("secret-db-password"))));
    }

    @Test
    void 메서드_보안의_권한_거부는_500이_아니라_403이다() throws Exception {
        mockMvc.perform(get("/test/denied"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void 지원하지_않는_메서드는_405와_Allow_헤더() throws Exception {
        mockMvc.perform(delete("/test/out-of-stock"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", containsString("GET")))
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }

    @Test
    void 깨진_JSON_본문은_400이고_Spring_기본_detail을_노출하지_않는다() throws Exception {
        mockMvc.perform(post("/test/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{broken"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.detail").doesNotExist());
    }

    @RestController
    static class TestController {

        @GetMapping("/test/out-of-stock")
        void outOfStock() {
            throw new BusinessException(ErrorCode.OUT_OF_STOCK, null,
                    Map.of("errors", List.of(Map.of("breadId", 22, "reason", "OUT_OF_STOCK", "remaining", 0))));
        }

        @GetMapping("/test/blocked")
        void blocked() {
            Map<String, Object> properties = new java.util.LinkedHashMap<>();
            properties.put("blockedUntil", "2026-10-29");
            properties.put("note", null);
            throw new BusinessException(ErrorCode.RESERVATION_BLOCKED, "2026-10-29까지 예약할 수 없습니다", properties);
        }

        @PostMapping("/test/validate")
        void validate(@Valid @RequestBody Request request) {
        }

        @GetMapping("/test/boom")
        void boom() {
            throw new IllegalStateException("secret-db-password");
        }

        @GetMapping("/test/denied")
        void denied() {
            throw new AccessDeniedException("denied");
        }

        record Request(@NotBlank String name, @Min(value = 1, message = "1 이상이어야 합니다") int quantity) {
        }
    }
}
