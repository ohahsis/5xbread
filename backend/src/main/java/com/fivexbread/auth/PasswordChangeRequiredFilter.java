package com.fivexbread.auth;

import com.fivexbread.common.BusinessException;
import com.fivexbread.common.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpMethod;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * 토큰에 pcr 표시가 있으면 비밀번호 변경·재발급·로그아웃 외의 요청을 403 PASSWORD_CHANGE_REQUIRED 로 막는다 (AU-06, AU-08, 13-5 4단계).
 * 화면 이동만으로는 API 를 직접 부르는 것을 막지 못한다. 토큰만 보므로 요청마다 DB·Redis 를 조회하지 않는다.
 *
 * 허용 목록 방식이라 새 API 를 만들어도 기본으로 막힌다. 비로그인 요청은 표시가 없어 그대로 지나간다.
 * JwtAuthenticationFilter 처럼 서블릿 필터로 자동 등록되지 않게 빈이 아니라 SecurityConfig 에서 직접 만든다.
 */
@RequiredArgsConstructor
public class PasswordChangeRequiredFilter extends OncePerRequestFilter {

    private static final List<RequestMatcher> ALLOWED = List.of(
            PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.PUT, "/api/v1/members/me/password"),
            PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.PUT, "/api/v1/auth/tokens/current"),      // 재발급
            PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.DELETE, "/api/v1/auth/tokens/current"));  // 로그아웃

    private final HandlerExceptionResolver exceptionResolver;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (passwordChangeRequired() && ALLOWED.stream().noneMatch(matcher -> matcher.matches(request))) {
            exceptionResolver.resolveException(request, response, null,
                    new BusinessException(ErrorCode.PASSWORD_CHANGE_REQUIRED));
            return;
        }
        chain.doFilter(request, response);
    }

    private static boolean passwordChangeRequired() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null
                && authentication.getPrincipal() instanceof AuthMember member
                && member.passwordChangeRequired();
    }
}
