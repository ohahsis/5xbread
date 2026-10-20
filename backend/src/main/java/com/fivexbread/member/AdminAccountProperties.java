package com.fivexbread.member;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 사장님 초기 계정 (decisions.md 2장, AU-08). 값은 .env 의 ADMIN_INITIAL_PHONE·ADMIN_INITIAL_PASSWORD 로만 주입한다.
 * 비어 있어도 앱은 뜬다 (AdminAccountInitializer 가 경고만 남기고 건너뜀).
 *
 * @param initialPhone    사장님 로그인 번호. 하이픈이 있어도 된다
 * @param initialPassword 초기 비밀번호 원문. BCrypt 해시만 저장하고 첫 로그인 때 변경을 강제한다
 */
@ConfigurationProperties("fivexbread.admin")
public record AdminAccountProperties(String initialPhone, String initialPassword) {

    // record 기본 toString 은 모든 값을 출력한다. 설정 덤프·로그에 원문이 남지 않게 가린다
    @Override
    public String toString() {
        return "AdminAccountProperties[initialPhone=%s, initialPassword=%s]"
                .formatted(initialPhone == null || initialPhone.isBlank() ? "(empty)" : "****",
                        initialPassword == null || initialPassword.isBlank() ? "(empty)" : "****");
    }
}
