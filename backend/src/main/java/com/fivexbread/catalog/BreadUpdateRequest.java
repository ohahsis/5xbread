package com.fivexbread.catalog;

import static com.fivexbread.catalog.BreadRules.*;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Set;

/**
 * PATCH /api/v1/admin/breads/{breadId} (A-01). null 인 필드는 바꾸지 않는다.
 * 설명은 빈 문자열이면 지우고, 알레르기는 빈 목록이면 지운다. 판매 중지는 active=false
 */
public record BreadUpdateRequest(
        @Size(min = 1, max = 30, message = NAME_MESSAGE) @Pattern(regexp = NAME_PATTERN, message = NAME_MESSAGE)
        String name,
        BreadCategory category,
        @Min(value = 100, message = PRICE_MESSAGE) @Max(value = 100_000, message = PRICE_MESSAGE)
        Integer price,
        @Size(max = 200, message = DESCRIPTION_MESSAGE) String description,
        Set<Allergen> allergens,
        @Min(value = 1, message = DISPLAY_ORDER_MESSAGE) @Max(value = 999, message = DISPLAY_ORDER_MESSAGE)
        Integer displayOrder,
        Boolean active
) {

    public BreadUpdateRequest {
        name = strip(name);
        description = strip(description);
    }

    @AssertTrue(message = PRICE_MESSAGE)
    boolean isPriceHundredUnit() {
        return isHundredUnit(price);
    }

    @AssertTrue(message = DESCRIPTION_LINES_MESSAGE)
    boolean isDescriptionWithinLineBreaks() {
        return withinLineBreaks(description);
    }
}
