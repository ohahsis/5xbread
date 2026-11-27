package com.fivexbread.domain.member.model.vo;

/** 가입 입력. 형식 검증(번호 숫자 11자리, 이름 앞뒤 공백 제거)을 마친 값 */
public record SignupCommand(String phone, String password, String name) {

    // record 기본 toString 은 모든 값을 출력한다. 로그·예외 메시지에 비밀번호·번호 원문이 남지 않게 가린다 (AU-10)
    @Override
    public String toString() {
        return "SignupCommand[***]";
    }
}
