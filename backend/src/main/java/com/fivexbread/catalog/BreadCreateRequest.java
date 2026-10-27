package com.fivexbread.catalog;

import static com.fivexbread.catalog.BreadRules.*;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Set;

/** POST /api/v1/admin/breads (A-01). 사진(imageId)은 업로드 API 와 함께 추가한다 */
public record BreadCreateRequest(
        @NotBlank(message = NAME_MESSAGE) @Size(max = 30, message = NAME_MESSAGE)
        @Pattern(regexp = NAME_PATTERN, message = NAME_MESSAGE)
        String name,
        @NotNull BreadCategory category,
        @NotNull(message = PRICE_MESSAGE) @Min(value = 100, message = PRICE_MESSAGE)
        @Max(value = 100_000, message = PRICE_MESSAGE)
        Integer price,
        @Size(max = 200, message = DESCRIPTION_MESSAGE) String description,
        Set<Allergen> allergens,
        @NotNull(message = DISPLAY_ORDER_MESSAGE) @Min(value = 1, message = DISPLAY_ORDER_MESSAGE)
        @Max(value = 999, message = DISPLAY_ORDER_MESSAGE)
        Integer displayOrder
) {

    public BreadCreateRequest {
        name = strip(name);
        description = description == null || description.isBlank() ? null : description.strip();
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
