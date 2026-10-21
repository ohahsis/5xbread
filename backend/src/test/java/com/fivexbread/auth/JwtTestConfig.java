package com.fivexbread.auth;

import com.fivexbread.common.ClockConfig;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;

/**
 * @WebMvcTest 는 @ConfigurationPropertiesScan·@Component 를 읽지 않아 SecurityConfig 가 쓰는 JwtProvider 를 직접 넣는다.
 * 비밀키는 application-test.yml (@ActiveProfiles("test")).
 */
@TestConfiguration(proxyBeanMethods = false)
@EnableConfigurationProperties(JwtProperties.class)
@Import({JwtProvider.class, ClockConfig.class})
class JwtTestConfig {
}
