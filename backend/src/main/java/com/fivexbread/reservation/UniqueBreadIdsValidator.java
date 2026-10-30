package com.fivexbread.reservation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public class UniqueBreadIdsValidator implements ConstraintValidator<UniqueBreadIds, List<ReservationCreateRequest.Item>> {

    @Override
    public boolean isValid(List<ReservationCreateRequest.Item> items, ConstraintValidatorContext context) {
        if (items == null) {
            return true;                                   // @NotNull 이 따로 본다
        }
        Set<Long> seen = new HashSet<>();
        return items.stream().filter(Objects::nonNull).map(ReservationCreateRequest.Item::breadId)
                .filter(Objects::nonNull).allMatch(seen::add);
    }
}
