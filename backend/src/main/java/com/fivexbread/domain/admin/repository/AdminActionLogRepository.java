package com.fivexbread.domain.admin.repository;

import com.fivexbread.domain.admin.model.AdminActionLog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AdminActionLogRepository extends JpaRepository<AdminActionLog, Long> {
}
