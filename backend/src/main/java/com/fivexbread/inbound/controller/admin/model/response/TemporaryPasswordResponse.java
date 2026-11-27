package com.fivexbread.inbound.controller.admin.model.response;

import com.fivexbread.domain.admin.model.vo.IssuedTemporaryPassword;

/** 이 응답에서 한 번만 내보낸다 (AU-06) */
public record TemporaryPasswordResponse(String temporaryPassword) {

    public static TemporaryPasswordResponse from(IssuedTemporaryPassword issued) {
        return new TemporaryPasswordResponse(issued.temporaryPassword());
    }


    // record 기본 toString 은 값을 그대로 출력한다. 로그·예외 메시지에 원문이 남지 않게 가린다 (AU-10)
    @Override
    public String toString() {
        return "TemporaryPasswordResponse[***]";
    }
}
