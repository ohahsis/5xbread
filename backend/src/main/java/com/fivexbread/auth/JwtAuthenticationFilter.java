package com.fivexbread.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authorization: Bearer 의 JWT 를 검증해 SecurityContext 에 회원 ID·권한(ROLE_ 접두사)을 넣는다 (정의서 13-6 2단계).
 * 토큰이 잘못돼도 여기서 바로 401을 내지 않고 익명으로 넘긴다. 인증이 필요한 경로면 EntryPoint 가 401을 낸다.
 * 재발급(PUT /auth/tokens/current)처럼 공개 경로는 만료된 토큰이 붙어 와도 그대로 동작해야 하기 때문이다.
 * 서블릿 필터로 자동 등록되지 않도록 빈이 아니라 SecurityConfig 에서 직접 만든다.
 */
@Slf4j
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER = "Bearer ";
    private static final String ROLE_PREFIX = "ROLE_";

    private final JwtProvider jwtProvider;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String token = resolveToken(request);
        if (token != null) {
            authenticate(token, request);
        }
        chain.doFilter(request, response);
    }

    private void authenticate(String token, HttpServletRequest request) {
        try {
            AuthMember member = jwtProvider.parse(token);
            var authorities = List.of(new SimpleGrantedAuthority(ROLE_PREFIX + member.role().name()));
            var authentication = UsernamePasswordAuthenticationToken.authenticated(member, null, authorities);
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authentication);
            SecurityContextHolder.setContext(context);
        } catch (InvalidTokenException e) {
            // 만료는 30분마다 정상적으로 생기고, 위조·형식 오류는 공격 신호일 수 있어 수준을 나눈다. 토큰 값은 남기지 않는다
            if (e.getReason() == InvalidTokenException.Reason.EXPIRED) {
                log.debug("만료된 Access Token uri={}", request.getRequestURI());
            } else {
                log.warn("잘못된 Access Token reason={} uri={} ip={}",
                        e.getReason(), request.getRequestURI(), request.getRemoteAddr());
            }
        }
    }

    // 인증 방식 이름(Bearer)은 대소문자를 구분하지 않는다 (RFC 6750 → RFC 9110 11.1)
    private static String resolveToken(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
            return null;
        }
        return header.substring(BEARER.length()).trim();
    }
}
