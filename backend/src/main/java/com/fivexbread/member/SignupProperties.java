package com.fivexbread.member;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param dailyLimitPerIp IP당 하루 가입 횟수 (AU-02, 부록 B: 설정 파일)
 * @param termsVersion    현재 서비스 이용약관 버전
 * @param privacyVersion  현재 개인정보 수집·이용 동의 버전
 */
@ConfigurationProperties("fivexbread.signup")
public record SignupProperties(int dailyLimitPerIp, String termsVersion, String privacyVersion) {
}
