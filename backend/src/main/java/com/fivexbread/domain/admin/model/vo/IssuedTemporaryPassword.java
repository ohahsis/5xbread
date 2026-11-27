package com.fivexbread.domain.admin.model.vo;

/** 새로 발급한 임시 비밀번호 원문. 응답으로 한 번만 내보낸다 (AU-06) */
public record IssuedTemporaryPassword(String temporaryPassword) {

    // record 기본 toString 은 값을 그대로 출력한다. 로그·예외 메시지에 원문이 남지 않게 가린다 (AU-10)
    @Override
    public String toString() {
        return "IssuedTemporaryPassword[***]";
    }
}
