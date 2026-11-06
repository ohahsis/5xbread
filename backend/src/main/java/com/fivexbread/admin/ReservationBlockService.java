package com.fivexbread.admin;

import com.fivexbread.common.BusinessException;
import com.fivexbread.common.ErrorCode;
import com.fivexbread.member.Member;
import com.fivexbread.member.MemberRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 노쇼 제한 해제 (A-10, R-17). 이미 한 예약과 노쇼 이력은 그대로이고 제한 날짜만 지운다 */
@Service
@RequiredArgsConstructor
public class ReservationBlockService {

    private final MemberRepository memberRepository;
    private final AdminActionLogRepository adminActionLogRepository;
    private final Clock clock;

    /** 해제와 이력을 한 트랜잭션에 남긴다. 실제로 지운 경우에만 이력을 쓴다. 활성 손님이 아니면 404 */
    @Transactional
    public AdminMemberResponse clear(long adminId, long memberId) {
        Member member = memberRepository.findById(memberId)
                .filter(Member::isActiveCustomer)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (memberRepository.clearReservationBlock(memberId) == 1) {
            adminActionLogRepository.save(AdminActionLog.onMember(
                    adminId, AdminAction.RESERVATION_BLOCK_CLEARED, memberId, LocalDateTime.now(clock)));
        }
        return AdminMemberResponse.from(member).withoutBlock();
    }
}
