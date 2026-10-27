package com.fivexbread.saledate;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DailyStockRepository extends JpaRepository<DailyStock, Long> {

    List<DailyStock> findBySaleDate(LocalDate saleDate);

    /**
     * 총량을 예약 수량보다 줄이지 않는 경우에만 바꾼다 (R-07). 0이면 검사 뒤 그 사이 예약이 들어온 것 → 저장 전체 롤백 후 422.
     * 조건이 없으면 ck_stock_range(reserved ≤ reservable) 위반 오류가 나고, 조건이 있으면 0행으로 끝난다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE daily_stock
            SET produced_qty = :producedQty, reservable_qty = :reservableQty, per_user_limit = :perUserLimit
            WHERE id = :id AND reserved_qty <= :reservableQty
            """, nativeQuery = true)
    int updateIfNotBelowReserved(@Param("id") long id, @Param("producedQty") int producedQty,
                                 @Param("reservableQty") int reservableQty, @Param("perUserLimit") Integer perUserLimit);

    /** 커밋된 최신 예약 수량. 트랜잭션의 스냅샷이 아니라 잠금 읽기로 본다 (위 UPDATE 가 0행일 때 응답에 넣을 값) */
    // 공유 잠금이에요. 다른 트랜잭션도 이 행을 읽을 수는 있지만, 바꾸려면 내 트랜잭션이 끝날 때까지 기다려요. 응답에 넣을 숫자를 읽는 동안 값이 또 바뀌지 않게 해요.
    // 이 트랜잭션은 곧바로 롤백되고 422로 끝나니, 잠금은 아주 잠깐만 유지돼요.
    // FOR UPDATE 는 읽기만 하면 되는데 다른 트랜잭션의 읽기까지 더 강하게 막아서, 여기서는 FOR SHARE 가 맞다. MySQL 8 이전 문법으로는 LOCK IN SHARE MODE 이다.
    @Query(value = "SELECT bread_id, reserved_qty FROM daily_stock WHERE id IN (:ids) FOR SHARE", nativeQuery = true)
    List<Object[]> findCurrentReserved(@Param("ids") Collection<Long> ids);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "DELETE FROM daily_stock WHERE sale_date = :saleDate", nativeQuery = true)
    int deleteBySaleDate(@Param("saleDate") LocalDate saleDate);
}
