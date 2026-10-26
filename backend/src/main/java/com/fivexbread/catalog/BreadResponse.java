package com.fivexbread.catalog;

import java.util.Set;

public record BreadResponse(
        long id,
        String name,
        BreadCategory category,
        int price,
        String description,
        Set<Allergen> allergens,
        int displayOrder,
        boolean active
) {

    static BreadResponse from(Bread bread) {
        return new BreadResponse(bread.getId(), bread.getName(), bread.getCategory(), bread.getPrice(),
                bread.getDescription(), Set.copyOf(bread.getAllergens()), bread.getDisplayOrder(), bread.isActive());
    }
}
