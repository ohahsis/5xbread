package com.fivexbread.reservation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** POST /api/v1/admin/pickup-code-verifications (C-07). 길이 상한은 토큰 형식(최대 66자)보다 넉넉히 */
public record PickupCodeVerificationRequest(@NotBlank @Size(max = 200) String code) {
}
