package com.fivexbread.saledate;

import java.time.LocalDate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SaleDateRepository extends JpaRepository<SaleDate, LocalDate> {

    /**
     * 수량 설정 버전 비교와 증가를 한 문장에 (decisions.md 6장 "A-02 수량 버전 증가"). 1이면 성공, 0이면 다른 저장이 먼저 올림 → 412.
     * 이 행을 잠그므로 같은 판매일의 수량 저장은 여기서 한 줄로 선다. 기다린 쪽은 커밋된 버전을 보고 0행을 받는다.
     */
    @Modifying(flushAutomatically = true)
    @Query(value = "UPDATE sale_date SET version = version + 1 WHERE sale_date = :saleDate AND version = :expected",
            nativeQuery = true)
    int increaseVersion(@Param("saleDate") LocalDate saleDate, @Param("expected") int expected);
}
