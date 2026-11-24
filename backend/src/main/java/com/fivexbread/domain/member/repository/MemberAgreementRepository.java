package com.fivexbread.domain.member.repository;

import com.fivexbread.domain.member.model.MemberAgreement;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MemberAgreementRepository extends JpaRepository<MemberAgreement, Long> {

    List<MemberAgreement> findByMemberId(Long memberId);
}
