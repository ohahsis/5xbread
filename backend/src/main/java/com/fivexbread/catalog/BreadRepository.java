package com.fivexbread.catalog;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BreadRepository extends JpaRepository<Bread, Long> {

    List<Bread> findAllByOrderByDisplayOrderAscNameAsc();

    List<Bread> findByActiveOrderByDisplayOrderAscNameAsc(boolean active);

    long countByActiveTrue();

    boolean existsByActiveName(String activeName);              // uk_bread_active_name 인덱스

    boolean existsByActiveNameAndIdNot(String activeName, Long id);
}
