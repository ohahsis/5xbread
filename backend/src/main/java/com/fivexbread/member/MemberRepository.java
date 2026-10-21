package com.fivexbread.member;

import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MemberRepository extends JpaRepository<Member, Long> {

    Optional<Member> findByPhone(String phone);                // uk_member_phone 인덱스. 탈퇴 회원은 phone 이 NULL 이라 찾지 못함

    boolean existsByRole(Role role);

    boolean existsByPhone(String phone);

    /**
     * 번호가 이미 있으면 넣지 않는 INSERT. 넣었으면 1, 중복이면 0.
     * 중복 키 오류를 내지 않아 Hibernate 가 번호 원문을 ERROR 로그에 남기지 않는다 (AU-10).
     * UNIQUE 는 uk_member_phone 하나뿐이라 0은 번호 중복만 뜻한다.
     * ON DUPLICATE KEY UPDATE 는 쓰지 않는다. MySQL 드라이버가 기본으로 "찾은 행 수"를 돌려줘 중복이어도 1이 나온다.
     * IGNORE 는 다른 오류(NOT NULL 등)도 경고로 바꾸므로, 값은 엔티티 팩터리(Member.signUp)가 채운 것만 넘긴다.
     */
    @Modifying
    @Query(value = """
            INSERT IGNORE INTO member (phone, password_hash, name, role, status, must_change_password, created_at)
            VALUES (:#{#m.phone}, :#{#m.passwordHash}, :#{#m.name}, :#{#m.role.name()}, :#{#m.status.name()},
                    :#{#m.mustChangePassword}, :#{#m.createdAt})
            """, nativeQuery = true)
    int insertIfPhoneAbsent(@Param("m") Member member);
}
