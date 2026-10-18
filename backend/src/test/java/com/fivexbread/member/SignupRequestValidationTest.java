package com.fivexbread.member;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SignupRequestValidationTest {

    private static final String PHONE = "01012345678";

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    // --- 비밀번호 경계값 ---

    @Test
    void 비밀번호_7자는_거절() {
        assertThat(passwordErrors("abcd123")).containsExactly(PasswordRules.RULE_MESSAGE);
    }

    @Test
    void 비밀번호_8자는_통과() {
        assertThat(passwordErrors("abcd1234")).isEmpty();
    }

    @Test
    void 비밀번호_64자는_통과() {
        String password = "ab12".repeat(16);
        assertThat(password).hasSize(64);
        assertThat(passwordErrors(password)).isEmpty();
    }

    @Test
    void 비밀번호_65자는_거절() {
        String password = "ab12".repeat(16) + "c";
        assertThat(password).hasSize(65);
        assertThat(passwordErrors(password)).containsExactly(PasswordRules.RULE_MESSAGE);
    }

    @Test
    void 숫자만은_거절() {
        assertThat(passwordErrors("20261017")).containsExactly(PasswordRules.RULE_MESSAGE);
    }

    @Test
    void 영문만은_거절() {
        assertThat(passwordErrors("breadbread")).containsExactly(PasswordRules.RULE_MESSAGE);
    }

    @Test
    void 같은_문자_4연속_aaaa는_거절() {
        assertThat(passwordErrors("bread2026aaaa")).containsExactly(PasswordRules.REPEAT_MESSAGE);
    }

    @Test
    void 같은_문자_3연속까지는_통과() {
        assertThat(passwordErrors("bread2026aaa")).isEmpty();
    }

    @Test
    void 휴대폰_번호_뒷_8자리를_포함하면_거절() {
        assertThat(passwordErrors("bread12345678")).containsExactly(PasswordRules.PHONE_MESSAGE);
    }

    @Test
    void 번호_일부만_겹치면_통과() {
        assertThat(passwordErrors("bread1234567")).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"bread 2026", "빵bread2026", "bread2026<>"})
    void 공백_한글_허용되지_않은_특수문자는_거절(String password) {
        assertThat(passwordErrors(password)).containsExactly(PasswordRules.RULE_MESSAGE);
    }

    @Test
    void 허용된_특수문자는_통과() {
        assertThat(passwordErrors("bread2026!@#$%^&*()-_=+[]{};:,.?/~")).isEmpty();
    }

    // --- 휴대폰 번호 ---

    @ParameterizedTest
    @ValueSource(strings = {"010-1234-5678", "010 1234 5678", "01012345678"})
    void 하이픈_공백을_빼고_숫자_11자리로_정규화(String phone) {
        SignupRequest request = request(phone, "bread2026!");
        assertThat(request.phone()).isEqualTo("01012345678");
        assertThat(errors(request, "phone")).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"011-1234-5678", "0101234567", "010123456789", "010-abcd-5678"})
    void 형식이_틀린_번호는_거절(String phone) {
        assertThat(errors(request(phone, "bread2026!"), "phone"))
                .containsExactly("올바른 휴대폰 번호를 입력해 주세요 (010으로 시작하는 11자리)");
    }

    // --- 이름·약관 ---

    @ParameterizedTest
    @ValueSource(strings = {"김", "김  동건", "김동건1", "가나다라마바사아자차카타파하가나다라마바사"})
    void 규칙에_맞지_않는_이름은_거절(String name) {
        SignupRequest request = new SignupRequest(PHONE, "bread2026!", name, agreed());
        assertThat(errors(request, "name")).isNotEmpty();
    }

    @Test
    void 이름_앞뒤_공백은_제거한_뒤_검사() {
        SignupRequest request = new SignupRequest(PHONE, "bread2026!", "  김동건 ", agreed());
        assertThat(request.name()).isEqualTo("김동건");
        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    void 필수_약관에_하나라도_동의하지_않으면_거절() {
        SignupRequest request = new SignupRequest(PHONE, "bread2026!", "김동건",
                new SignupRequest.Agreements(true, false));
        assertThat(errors(request, "agreements.privacy")).containsExactly("필수 약관에 동의해 주세요");
    }

    private Set<String> passwordErrors(String password) {
        return errors(request(PHONE, password), "password");
    }

    private Set<String> errors(SignupRequest request, String property) {
        Set<ConstraintViolation<SignupRequest>> violations = validator.validate(request);
        return violations.stream()
                .filter(v -> v.getPropertyPath().toString().equals(property))
                .map(ConstraintViolation::getMessage)
                .collect(Collectors.toSet());
    }

    private static SignupRequest request(String phone, String password) {
        return new SignupRequest(phone, password, "김동건", agreed());
    }

    private static SignupRequest.Agreements agreed() {
        return new SignupRequest.Agreements(true, true);
    }
}
