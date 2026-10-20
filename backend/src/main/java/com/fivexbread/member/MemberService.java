package com.fivexbread.member;

import com.fivexbread.common.BusinessException;
import com.fivexbread.common.ErrorCode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MemberService {

    private static final String UK_MEMBER_PHONE = "uk_member_phone";

    private final MemberRepository memberRepository;
    private final MemberAgreementRepository agreementRepository;
    private final SignupProperties properties;
    private final Clock clock;

    /**
     * 회원과 약관 동의를 한 트랜잭션에 저장한다.
     * 중복 번호는 미리 SELECT로 확인하지 않고 uk_member_phone 제약에 맡긴다.
     * 동시에 들어온 같은 번호 요청이 둘 다 "없음"을 보고 통과하는 경쟁 조건을 DB가 막는다 (13-5).
     */
    @Transactional
    public Member signUp(String phone, String passwordHash, String name) {
        LocalDateTime now = LocalDateTime.now(clock);
        Member member;
        try {
            member = memberRepository.saveAndFlush(Member.signUp(phone, passwordHash, name, now));
        } catch (DataIntegrityViolationException e) {
            if (isDuplicatePhone(e)) {
                throw new BusinessException(ErrorCode.DUPLICATE_PHONE);
            }
            throw e;
        }
        agreementRepository.save(new MemberAgreement(
                member.getId(), properties.termsVersion(), properties.privacyVersion(), now));
        return member;
    }

    @Transactional(readOnly = true)
    public Optional<Member> findByPhone(String phone) {
        return memberRepository.findByPhone(phone);
    }

    // MySQL: Duplicate entry '...' for key 'member.uk_member_phone'
    private static boolean isDuplicatePhone(DataIntegrityViolationException e) {
        String message = e.getMostSpecificCause().getMessage();
        return message != null && message.contains(UK_MEMBER_PHONE);
    }
}
