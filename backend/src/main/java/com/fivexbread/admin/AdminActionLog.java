package com.fivexbread.admin;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * V1 admin_action_log. 누가(admin_id) 무엇을(action) 누구에게(target) 언제(created_at) 했는지 남긴다.
 * detail(JSON)은 수량 재계산(A-12)의 빵별 결과만 쓴다. 임시 비밀번호 원문·해시는 넣지 않는다.
 */
@Entity
@Table(name = "admin_action_log")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AdminActionLog {

    private static final String TARGET_MEMBER = "MEMBER";
    private static final String TARGET_SALE_DATE = "SALE_DATE";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long adminId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 30)
    private AdminAction action;

    @Column(nullable = false, length = 20)
    private String targetType;

    @Column(nullable = false, length = 20)
    private String targetId;

    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> detail;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private AdminActionLog(long adminId, AdminAction action, String targetType, String targetId,
                           Map<String, Object> detail, LocalDateTime now) {
        this.adminId = adminId;
        this.action = action;
        this.targetType = targetType;
        this.targetId = targetId;
        this.detail = detail;
        this.createdAt = now;
    }

    public static AdminActionLog onMember(long adminId, AdminAction action, long memberId, LocalDateTime now) {
        return new AdminActionLog(adminId, action, TARGET_MEMBER, String.valueOf(memberId), null, now);
    }

    public static AdminActionLog onSaleDate(long adminId, AdminAction action, LocalDate saleDate,
                                            Map<String, Object> detail, LocalDateTime now) {
        return new AdminActionLog(adminId, action, TARGET_SALE_DATE, saleDate.toString(), detail, now);
    }
}
