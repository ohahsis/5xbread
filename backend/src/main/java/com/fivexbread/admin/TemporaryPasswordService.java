package com.fivexbread.admin;

import com.fivexbread.common.BusinessException;
import com.fivexbread.common.ErrorCode;
import com.fivexbread.member.MemberRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class TemporaryPasswordService {

    private final MemberRepository memberRepository;
    private final AdminActionLogRepository adminActionLogRepository;
    private final Clock clock;

    /** 비밀번호 저장과 발급 이력을 한 트랜잭션에 남긴다. 이력 없는 발급이 생기면 안 된다 (A-09) */
    @Transactional
    public void save(long adminId, long memberId, String passwordHash) {
        if (memberRepository.setTemporaryPassword(memberId, passwordHash) == 0) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        adminActionLogRepository.save(AdminActionLog.onMember(
                adminId, AdminAction.TEMPORARY_PASSWORD_ISSUED, memberId, LocalDateTime.now(clock)));
    }
}
