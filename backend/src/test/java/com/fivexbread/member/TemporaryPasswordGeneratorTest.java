package com.fivexbread.member;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TemporaryPasswordGeneratorTest {

    private static final String PHONE = "01012345678";

    @Test
    void 영문_숫자_12자이고_언제나_비밀번호_규칙을_지킨다() {
        Set<String> generated = new HashSet<>();
        for (int i = 0; i < 5_000; i++) {     // 재시도가 없으면 약 12%가 숫자 없이 나온다
            String password = TemporaryPasswordGenerator.generate(PHONE);

            assertThat(password).matches("^[A-Za-z0-9]{12}$");
            assertThat(PasswordRules.meetsBasicRule(password)).isTrue();
            assertThat(PasswordRules.hasRepeatedChars(password)).isFalse();
            assertThat(PasswordRules.containsPhone(password, PHONE)).isFalse();
            generated.add(password);
        }
        assertThat(generated).hasSize(5_000);
    }
}
