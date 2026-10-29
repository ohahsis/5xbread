package com.fivexbread.saledate;

import static com.fivexbread.common.ClockConfig.KST;

import java.time.OffsetDateTime;

/** 손님용 픽업 시간대 (R-12). 예약 요청에 pickupSlotId 로 보낸다 */
public record PickupSlotResponse(long pickupSlotId, OffsetDateTime pickupAt) {

    static PickupSlotResponse of(PickupSlot slot) {
        return new PickupSlotResponse(slot.getId(), slot.getPickupAt().atZone(KST).toOffsetDateTime());
    }
}
