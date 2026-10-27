package com.fivexbread.saledate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** V1 pickup_slot 테이블 (R-12). 예약이 id 로 가리키므로 시간대를 바꿀 때 남는 시간대는 지우지 않고 그대로 둔다 */
@Entity
@Table(name = "pickup_slot")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PickupSlot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private LocalDate saleDate;

    @Column(nullable = false)
    private LocalDateTime pickupAt;

    public PickupSlot(LocalDate saleDate, LocalDateTime pickupAt) {
        this.saleDate = saleDate;
        this.pickupAt = pickupAt;
    }
}
