package com.fivexbread.member;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** V1 member_agreement 테이블. 동의한 약관 버전과 시각 (9장 필드 규격 "동의 시각과 약관 버전 저장") */
@Entity
@Table(name = "member_agreement")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MemberAgreement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long memberId;                                 // 연관관계 대신 ID만 (정의서 13장 엔티티 예시)

    @Column(nullable = false, length = 10)
    private String termsVersion;

    @Column(nullable = false, length = 10)
    private String privacyVersion;

    @Column(nullable = false)
    private LocalDateTime agreedAt;

    public MemberAgreement(Long memberId, String termsVersion, String privacyVersion, LocalDateTime agreedAt) {
        this.memberId = memberId;
        this.termsVersion = termsVersion;
        this.privacyVersion = privacyVersion;
        this.agreedAt = agreedAt;
    }
}
