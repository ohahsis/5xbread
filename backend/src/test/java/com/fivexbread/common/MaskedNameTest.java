package com.fivexbread.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MaskedNameTest {

    @Test
    void 첫_글자와_마지막_글자만_남긴다() {
        assertThat(MaskedName.from("김민건").value()).isEqualTo("김*건");
        assertThat(MaskedName.from("남궁민수").value()).isEqualTo("남**수");
        assertThat(MaskedName.from("이건").value()).isEqualTo("이*");
        assertThat(MaskedName.from("김").value()).isEqualTo("*");
        assertThat(MaskedName.from(" 김민건 ").value()).isEqualTo("김*건");
    }

    @Test
    void 이모지도_글자_하나로_센다() {
        assertThat(MaskedName.from("빵😀이").value()).isEqualTo("빵*이");
    }

    @Test
    void 비어_있으면_null() {
        assertThat(MaskedName.from(null).value()).isNull();
        assertThat(MaskedName.from(" ").value()).isNull();
    }
}
