package com.fivexbread.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fivexbread.common.ApiExceptionHandler;
import com.fivexbread.common.BusinessException;
import com.fivexbread.common.ErrorCode;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 재발급·로그아웃 컨트롤러: 쿠키 읽기·쓰기와 상태 코드만 확인한다. 회전 로직(AuthFacade)은 목으로 대신한다.
 */
@WebMvcTest(controllers = AuthController.class)
@Import({SecurityConfig.class, ApiExceptionHandler.class, JwtTestConfig.class})
@ActiveProfiles("test")
class RefreshTokenControllerTest {

    private static final String URL = "/api/v1/auth/tokens/current";

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    AuthFacade authFacade;

    @Test
    void 재발급하면_200_새_Access_Token과_새_Refresh_쿠키() throws Exception {
        given(authFacade.reissue("old-refresh"))
                .willReturn(new IssuedTokens("new-access", 1800, false, "new-refresh", Duration.ofDays(14)));

        String setCookie = mockMvc.perform(put(URL).cookie(new Cookie("refresh_token", "old-refresh")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("new-access"))
                .andExpect(jsonPath("$.expiresIn").value(1800))
                .andExpect(jsonPath("$.mustChangePassword").value(false))
                .andExpect(jsonPath("$.refreshToken").doesNotExist())            // 원문은 쿠키로만
                .andReturn().getResponse().getHeader(HttpHeaders.SET_COOKIE);

        assertThat(setCookie).startsWith("refresh_token=new-refresh;")
                .contains("Path=/api/v1/auth", "Max-Age=1209600", "Secure", "HttpOnly", "SameSite=Strict");
    }

    @Test
    void 재발급에_쿠키가_없으면_401이고_Facade를_부르지_않는다() throws Exception {
        mockMvc.perform(put(URL))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));

        verify(authFacade, never()).reissue(any());
    }

    @Test
    void 재발급이_거절되면_401_ProblemDetail이고_새_쿠키를_주지_않는다() throws Exception {
        given(authFacade.reissue("reused-refresh")).willThrow(new BusinessException(ErrorCode.UNAUTHENTICATED));

        mockMvc.perform(put(URL).cookie(new Cookie("refresh_token", "reused-refresh")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
    }

    @Test
    void 로그아웃하면_204와_만료된_쿠키() throws Exception {
        String setCookie = mockMvc.perform(delete(URL).cookie(new Cookie("refresh_token", "my-refresh")))
                .andExpect(status().isNoContent())
                .andReturn().getResponse().getHeader(HttpHeaders.SET_COOKIE);

        verify(authFacade).logout("my-refresh");
        assertThat(setCookie).startsWith("refresh_token=;").contains("Path=/api/v1/auth", "Max-Age=0");
    }

    @Test
    void 쿠키_없이_로그아웃해도_204이고_Facade를_부르지_않는다() throws Exception {
        mockMvc.perform(delete(URL))
                .andExpect(status().isNoContent())
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Max-Age=0")));

        verify(authFacade, never()).logout(any());
    }

    @Test
    void 만료된_Access_Token이_붙어_와도_재발급할_수_있다() throws Exception {
        given(authFacade.reissue("old-refresh"))
                .willReturn(new IssuedTokens("new-access", 1800, false, "new-refresh", Duration.ofDays(14)));

        mockMvc.perform(put(URL)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer expired.or.broken")
                        .cookie(new Cookie("refresh_token", "old-refresh")))
                .andExpect(status().isOk());
    }

    @Test
    void IssuedTokens의_toString에_토큰_원문이_없다() {
        String text = new IssuedTokens("access-raw", 1800, false, "refresh-raw", Duration.ofDays(14)).toString();

        assertThat(text).doesNotContain("access-raw", "refresh-raw");
    }
}
