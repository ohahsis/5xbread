package com.fivexbread.inbound.controller.catalog.model.response;

import com.fivexbread.domain.catalog.model.enums.Allergen;
import com.fivexbread.domain.catalog.model.Bread;
import com.fivexbread.domain.catalog.model.enums.BreadCategory;
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

    public static BreadResponse from(Bread bread) {
        return new BreadResponse(bread.getId(), bread.getName(), bread.getCategory(), bread.getPrice(),
                bread.getDescription(), Set.copyOf(bread.getAllergens()), bread.getDisplayOrder(), bread.isActive());
    }
}
