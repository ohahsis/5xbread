package com.fivexbread.member;

import com.fivexbread.common.MaskedPhone;
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
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * V1 member 테이블.
 * MySQL에서 Hibernate는 Enum을 ENUM 타입, String을 VARCHAR로 기대하므로
 * DDL의 VARCHAR·CHAR 컬럼에 맞게 @JdbcTypeCode로 지정한다 (ddl-auto: validate).
 */
@Entity
@Table(name = "member")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Member {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 11)
    private String phone;                                  // 탈퇴 시 NULL

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 60)
    private String passwordHash;                           // BCrypt

    @Column(nullable = false, length = 20)
    private String name;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 10)
    private Role role;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 10)
    private MemberStatus status;

    @Column(nullable = false)
    private boolean mustChangePassword;

    private LocalDate reservationBlockedUntil;             // R-17

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(length = 64)
    private String withdrawnPhoneHash;                     // 탈퇴 30일 재가입 제한

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private LocalDateTime withdrawnAt;

    private Member(String phone, String passwordHash, String name, Role role, LocalDateTime now) {
        this.phone = phone;
        this.passwordHash = passwordHash;
        this.name = name;
        this.role = role;
        this.status = MemberStatus.ACTIVE;
        this.mustChangePassword = false;
        this.createdAt = now;
    }

    /** 손님 가입. phone은 숫자 11자리로 정규화된 값, passwordHash는 BCrypt 해시 */
    public static Member signUp(String phone, String passwordHash, String name, LocalDateTime now) {
        return new Member(phone, passwordHash, name, Role.CUSTOMER, now);
    }

    public MaskedPhone maskedPhone() {
        return MaskedPhone.from(phone);
    }
}
