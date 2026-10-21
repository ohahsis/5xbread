package com.fivexbread.member;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MemberAgreementRepository extends JpaRepository<MemberAgreement, Long> {

    List<MemberAgreement> findByMemberId(Long memberId);
}
