package com.fivexbread.member;

import com.fivexbread.common.BusinessException;
import com.fivexbread.common.ErrorCode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 탈퇴 DB 트랜잭션 (AU-07, 13-5 5단계). Redis·BCrypt 는 부르지 않는다 (AuthFacade.withdraw 가 앞뒤에서 처리).
 *
 * 1. 회원 행을 FOR UPDATE 로 잠근다. 예약 트랜잭션도 같은 행을 먼저 잠그므로(ReservationTxService) 탈퇴와 예약이 줄을 선다.
 *    - 예약이 먼저 커밋하면 잠금을 얻은 뒤 RESERVED 를 세어 409
 *    - 탈퇴가 먼저 커밋하면 예약 트랜잭션이 잠근 뒤 WITHDRAWN 을 보고 401(Redis 차감은 보상)
 * 2. READ COMMITTED: 잠금을 얻은 뒤의 COUNT 가 그 사이 커밋된 예약을 본다. REPEATABLE READ 라면 트랜잭션 첫 읽기 시점의
 *    스냅샷이라, 잠금 전에 읽은 것이 있으면 먼저 커밋된 예약을 못 볼 수 있다.
 * 3. 비밀번호 확인 뒤 바뀌었으면(동시 비밀번호 변경·임시 비밀번호) 422. 확인한 해시를 조건으로 건다(C-10 과 같은 방식)
 * 4. 회원 행 익명화 + 그 회원 예약의 번호 뒷 4자리를 '****' 로. 예약·항목·이력·약관 동의 행은 통계·정합성을 위해 남긴다.
 *
 * 잠금 순서: 회원 → 예약. 예약 생성(회원 → 재고 → 예약 INSERT)과 같은 방향이고, 취소·수령·노쇼(예약 → 재고)는 회원을 잠그지 않으며
 * 노쇼 제한(applyBlocks)은 회원만 잠가 순환 대기가 생기지 않는다.
 */
@Service
@RequiredArgsConstructor
public class WithdrawalService {

    static final String WITHDRAWN_NAME = "탈퇴회원";                   // 13-5
    /** BCrypt 형식이 아닌 값이라 어떤 비밀번호와도 맞지 않는다. 번호가 NULL 이라 로그인 조회에도 걸리지 않는다 */
    static final String NO_PASSWORD = "!withdrawn" + "-".repeat(50);  // CHAR(60)

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final Clock clock;

    /**
     * @param checkedHash 비밀번호를 확인한 시점의 해시
     * @param phoneHash   WithdrawnPhoneHasher 로 만든 번호 해시
     * @throws BusinessException 이미 탈퇴 401, 비밀번호가 그사이 바뀜 422, RESERVED 예약이 있음 409
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void withdraw(long memberId, String checkedHash, String phoneHash) {
        Map<String, Object> id = Map.of("id", memberId);
        Map<String, Object> locked = jdbcTemplate.queryForMap(
                "SELECT status, password_hash FROM member WHERE id = :id FOR UPDATE", id);
        if (!MemberStatus.ACTIVE.name().equals(locked.get("status"))) {
            throw new BusinessException(ErrorCode.UNAUTHENTICATED);           // 동시 탈퇴의 두 번째 요청
        }
        if (!checkedHash.equals(locked.get("password_hash"))) {
            throw new BusinessException(ErrorCode.INVALID_CURRENT_PASSWORD);
        }
        Long reserved = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reservation WHERE member_id = :id AND status = 'RESERVED'", id, Long.class);
        if (reserved != null && reserved > 0) {
            throw new BusinessException(ErrorCode.ACTIVE_RESERVATION_EXISTS);
        }
        jdbcTemplate.update("""
                UPDATE member SET name = :name, phone = NULL, password_hash = :password, status = 'WITHDRAWN',
                                  must_change_password = FALSE, reservation_blocked_until = NULL,
                                  withdrawn_phone_hash = :phoneHash, withdrawn_at = :now
                WHERE id = :id""", Map.of("id", memberId, "name", WITHDRAWN_NAME, "password", NO_PASSWORD,
                "phoneHash", phoneHash, "now", LocalDateTime.now(clock)));
        jdbcTemplate.update("UPDATE reservation SET phone_last4 = '****' WHERE member_id = :id", id);
    }

    /**
     * 재가입 제한 기간이 지난 번호 해시를 지운다 (13-5 "30일이 지나면 배치로 삭제"). 회원 행은 남는다.
     *
     * @return 지운 행 수
     */
    @Transactional
    public int clearExpiredPhoneHashes(LocalDateTime withdrawnBefore) {
        return jdbcTemplate.update("""
                UPDATE member SET withdrawn_phone_hash = NULL
                WHERE withdrawn_phone_hash IS NOT NULL AND withdrawn_at < :before""",
                Map.of("before", withdrawnBefore));
    }
}
