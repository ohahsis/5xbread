package com.fivexbread.saledate;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PickupSlotRepository extends JpaRepository<PickupSlot, Long> {

    List<PickupSlot> findBySaleDateOrderByPickupAt(LocalDate saleDate);

    /** 예약(상태 무관)이 가리키는 시간대. fk_resv_slot 때문에 취소된 예약이 있어도 지울 수 없다 */
    @Query(value = "SELECT DISTINCT pickup_slot_id FROM reservation WHERE pickup_slot_id IN (:slotIds)",
            nativeQuery = true)
    List<Long> findSlotIdsWithReservations(@Param("slotIds") Collection<Long> slotIds);

    @Query(value = "SELECT EXISTS (SELECT 1 FROM reservation WHERE sale_date = :saleDate)", nativeQuery = true)
    long existsReservationOn(@Param("saleDate") LocalDate saleDate);
}
