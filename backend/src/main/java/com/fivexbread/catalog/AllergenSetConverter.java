package com.fivexbread.catalog;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

/** bread.allergens VARCHAR(100) ↔ Set. 순서는 enum 선언 순서로 고정, 없으면 NULL */
@Converter
class AllergenSetConverter implements AttributeConverter<Set<Allergen>, String> {

    @Override
    public String convertToDatabaseColumn(Set<Allergen> allergens) {
        if (allergens == null || allergens.isEmpty()) { // 빈 집합을 먼저 걸러낸다. EnumSet.copyOf() 는 비어있는 Set 을 받으면 어떤 예외인지 알 수 없어 오류를 던지기 때문이다.
            return null;    // 비어 있으면 DB 에는 NULL
        }
        return EnumSet.copyOf(allergens).stream()           // 정렬(같은 집합이 항상 같은 문자열이 된다: EnumSet 으로 한 번 감쌌기 때문에, 사용자가 넣는 순서와는 상관 없이 DB 에는 항상 ENUM 에 선언된 순서로 들어간다.)
                .map(Enum::name)                            // MILK -> "MILK"
                .collect(Collectors.joining(","));  // "WHEAT,MILK,EGG"
    }

    @Override
    public Set<Allergen> convertToEntityAttribute(String column) {
        if (column == null || column.isBlank()) {   // NULL → 빈 집합
            return EnumSet.noneOf(Allergen.class);
        }
        return Arrays.stream(column.split(",")) // ["WHEAT","MILK","EGG"]
                .map(Allergen::valueOf)                 // "MILK" -> MILK
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(Allergen.class)));    // EnumSet 으로 모으기
    }
}
