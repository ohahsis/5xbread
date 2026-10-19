package com.fivexbread.auth;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * pod 3개라 서버 세션 없이 JWT 만 쓴다 (정의서 5장). Bearer 토큰은 JwtAuthenticationFilter 가 검증한다.
 * 필터 단계의 401·403도 ApiExceptionHandler 로 넘겨 다른 오류와 같은 ProblemDetail 형식으로 응답한다.
 */
@Configuration
public class SecurityConfig {

    private static final String[] SWAGGER = {"/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**"};

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            JwtProvider jwtProvider,
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver exceptionResolver) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)            // 쿠키 세션이 아닌 Bearer 헤더 인증
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)          // 로그아웃은 DELETE /auth/tokens/current
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))   // 앱 3개라 서버 세션을 사용하지 않음(JWT 토큰으로 인증)
                .addFilterBefore(new JwtAuthenticationFilter(jwtProvider), UsernamePasswordAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/error").permitAll()     // 오류 포워드가 401로 덮이지 않게
                        .requestMatchers("/actuator/health/**").permitAll()
                        .requestMatchers(SWAGGER).permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/members").permitAll()        // 가입: HTTP 메서드를 GET 까지 열 경우 로그인 없이도 정보가 열릴 수 있음
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/tokens").permitAll()    // 로그인
                        // 재발급·로그아웃은 Access Token이 아니라 Refresh 쿠키로 인증 (만료된 Access Token으로도 호출)
                        .requestMatchers(HttpMethod.PUT, "/api/v1/auth/tokens/current").permitAll()
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/auth/tokens/current").permitAll()
                        // 판매 정보 조회는 "공개" (정의서 10장)
                        .requestMatchers(HttpMethod.GET, "/api/v1/sale-dates/**", "/api/v1/breads/**").permitAll()
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        .anyRequest().authenticated())  // 가장 마지막에 위치해야 한다. 순서대로 적용되기 때문에 이게 앞에 있을 경우 permitAll 이 무시된다.
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint((request, response, ex) ->
                                exceptionResolver.resolveException(request, response, null, ex))
                        .accessDeniedHandler((request, response, ex) ->
                                exceptionResolver.resolveException(request, response, null, ex)))
                .build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(10);                     // AU-04, 부록 B: cost 10 고정
    }
}
