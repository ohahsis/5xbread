package com.fivexbread.domain.catalog.model.vo;

import com.fivexbread.domain.catalog.model.enums.Allergen;
import com.fivexbread.domain.catalog.model.enums.BreadCategory;
import java.util.Set;

/**
 * 빵 등록·수정 입력. 수정에서는 null 인 필드를 바꾸지 않는다(설명은 빈 문자열이면 지움, 알레르기는 빈 목록이면 지움).
 * 등록은 {@code active} 를 쓰지 않는다(새 빵은 판매 중).
 */
public record BreadCommand(
        String name,
        BreadCategory category,
        Integer price,
        String description,
        Set<Allergen> allergens,
        Integer displayOrder,
        Boolean active
) {
}
