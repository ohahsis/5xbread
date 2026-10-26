package com.fivexbread.catalog;

/** 빵 필드 규칙 (9장 필드 규격 "빵"). 등록·수정 요청이 함께 쓴다 */
final class BreadRules {

    static final String NAME_PATTERN = "^[가-힣A-Za-z0-9 ()&\\-·']+$";   // 한글, 영문, 숫자, 공백, ( ) & - · '
    static final String NAME_MESSAGE = "빵 이름은 1~30자로 입력해 주세요";
    static final String PRICE_MESSAGE = "가격은 100원 단위로 100~100,000원 사이여야 합니다";
    static final String DESCRIPTION_MESSAGE = "설명은 200자 이내로 입력해 주세요";
    static final String DESCRIPTION_LINES_MESSAGE = "설명의 줄바꿈은 3개까지입니다";
    static final String DISPLAY_ORDER_MESSAGE = "노출 순서는 1~999 사이여야 합니다";
    static final int MAX_DESCRIPTION_LINE_BREAKS = 3;

    private BreadRules() {
    }

    static boolean isHundredUnit(Integer price) {
        return price == null || price % 100 == 0;
    }

    static boolean withinLineBreaks(String description) {
        return description == null || description.chars().filter(c -> c == '\n').count() <= MAX_DESCRIPTION_LINE_BREAKS;
    }

    static String strip(String value) {
        return value == null ? null : value.strip();
    }
}
