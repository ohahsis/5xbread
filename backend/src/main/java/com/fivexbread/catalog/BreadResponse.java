package com.fivexbread.catalog;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Set;

public record BreadResponse(
        long id,
        String name,
        BreadCategory category,
        int price,
        @Schema(nullable = true) String description,
        Set<Allergen> allergens,
        int displayOrder,
        boolean active
) {

    static BreadResponse from(Bread bread) {
        return new BreadResponse(bread.getId(), bread.getName(), bread.getCategory(), bread.getPrice(),
                bread.getDescription(), Set.copyOf(bread.getAllergens()), bread.getDisplayOrder(), bread.isActive());
    }
}
