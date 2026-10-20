package com.fivexbread.member;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MemberRepository extends JpaRepository<Member, Long> {

    Optional<Member> findByPhone(String phone);                // uk_member_phone 인덱스. 탈퇴 회원은 phone 이 NULL 이라 찾지 못함
}
