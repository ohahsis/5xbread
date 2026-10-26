package com.fivexbread.catalog;

/** 알레르기 유발물질 표시 대상 (식약처 고시, 9장 필드 규격 "알레르기 성분"). DB 에는 "WHEAT,MILK,EGG" */
public enum Allergen {
    EGG,          // 알류
    MILK,         // 우유
    BUCKWHEAT,    // 메밀
    PEANUT,       // 땅콩
    SOYBEAN,      // 대두
    WHEAT,        // 밀
    MACKEREL,     // 고등어
    CRAB,         // 게
    SHRIMP,       // 새우
    PORK,         // 돼지고기
    PEACH,        // 복숭아
    TOMATO,       // 토마토
    SULFITE,      // 아황산류
    WALNUT,       // 호두
    CHICKEN,      // 닭고기
    BEEF,         // 쇠고기
    SQUID,        // 오징어
    SHELLFISH,    // 조개류
    PINE_NUT      // 잣
}
