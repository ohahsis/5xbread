package com.fivexbread.member;

import com.fivexbread.common.BusinessException;
import com.fivexbread.common.ErrorCode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MemberService {

    private final MemberRepository memberRepository;
    private final MemberAgreementRepository agreementRepository;
    private final SignupProperties properties;
    private final Clock clock;

    /**
     * 회원과 약관 동의를 한 트랜잭션에 저장한다.
     * 중복 번호는 미리 SELECT로 확인하지 않고 uk_member_phone 제약에 맡긴다.
     * 동시에 들어온 같은 번호 요청이 둘 다 "없음"을 보고 통과하는 경쟁 조건을 DB가 막는다 (13-5).
     * 중복이면 오류 대신 0행을 돌려받는 INSERT 를 쓴다. 중복 키 오류는 Hibernate 가 번호 원문과 함께 로그에 남기기 때문 (AU-10)
     */
    @Transactional
    public Member signUp(String phone, String passwordHash, String name) {
        LocalDateTime now = LocalDateTime.now(clock);
        if (memberRepository.insertIfPhoneAbsent(Member.signUp(phone, passwordHash, name, now)) == 0) {
            throw new BusinessException(ErrorCode.DUPLICATE_PHONE);
        }
        Member member = memberRepository.findByPhone(phone).orElseThrow();   // 같은 트랜잭션이 넣은 행이라 반드시 보인다
        agreementRepository.save(new MemberAgreement(
                member.getId(), properties.termsVersion(), properties.privacyVersion(), now));
        return member;
    }

    @Transactional(readOnly = true)
    public Optional<Member> findByPhone(String phone) {
        return memberRepository.findByPhone(phone);
    }

    @Transactional(readOnly = true)
    public Optional<Member> findById(long memberId) {
        return memberRepository.findById(memberId);
    }

    @Transactional(readOnly = true)
    public boolean existsAdmin() {
        return memberRepository.existsByRole(Role.ADMIN);
    }

    /**
     * 사장님 초기 계정 생성 (decisions.md 2장). ADMIN 이 이미 있으면 false.
     * 번호는 INSERT 전에 확인한다. 중복 키 오류가 나면 Hibernate 가 번호 원문을 ERROR 로그에 남기기 때문 (AU-10)
     *
     * @throws BusinessException 그 번호를 이미 다른 회원이 쓰고 있으면 DUPLICATE_PHONE
     */
    @Transactional
    public boolean createInitialAdmin(String phone, String passwordHash) {
        if (memberRepository.existsByRole(Role.ADMIN)) {
            return false;
        }
        if (memberRepository.existsByPhone(phone)) {
            throw new BusinessException(ErrorCode.DUPLICATE_PHONE);
        }
        // 트랜잭션 동안 다른 앱은 GET_LOCK 잠금된 상태이기 때문에, 트랜잭션 끝나기 전에 실제 DB 에도 저장되어야 한다.
        // 따라서 트랜잭션 이후 flush 되는 save() 대신, 바로 되는 saveAndFlush() 를 사용한다.
        memberRepository.saveAndFlush(Member.initialAdmin(phone, passwordHash, LocalDateTime.now(clock)));
        return true;
    }
}
