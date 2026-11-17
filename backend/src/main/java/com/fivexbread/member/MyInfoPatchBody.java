package com.fivexbread.member;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;

/** 내 정보 PATCH 본문의 OpenAPI 문서용 모양 (C-10). 실제로는 Map 으로 받아 MyInfoPatch 가 검사한다 */
@Schema(name = "MyInfoPatch", description = "이름 수정. 이 필드만 받는다")
record MyInfoPatchBody(
        @Schema(requiredMode = RequiredMode.REQUIRED, minLength = 2, maxLength = 20,
                description = "한글 완성형·영문, 단어 사이 공백 하나. 앞뒤 공백은 지운 뒤 센다")
        String name) {
}
