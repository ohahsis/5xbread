package com.fivexbread.reservation;

import com.fivexbread.member.MemberRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** 노쇼 묶음 트랜잭션 (A-07, R-17). 반복은 NoShowBatch 가 하고, 여기는 묶음 하나씩 짧게 커밋한다 */
@Service
@RequiredArgsConstructor
public class NoShowService {

    private final ReservationRepository reservationRepository;
    private final MemberRepository memberRepository;
    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final Clock clock;

    /**
     * @param candidates 이번에 찾은 RESERVED 수. 0 이면 남은 예약이 없다
     * @param marked     실제로 NO_SHOW 가 된 수. 그사이 수령·취소된 예약만큼 candidates 보다 적을 수 있다
     */
    public record Chunk(int candidates, int marked) {
    }

    /** 상태 변경과 이력을 한 트랜잭션에 남긴다. 이력(actor 없음 = 시스템) 없이 바뀐 예약이 생기지 않는다 */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Chunk markChunk(LocalDate saleDate, int size) {
        List<Long> candidates = reservationRepository.findReservedIds(saleDate, size);
        if (candidates.isEmpty()) {
            return new Chunk(0, 0);
        }
        List<Long> locked = reservationRepository.lockReserved(candidates);
        if (locked.isEmpty()) {
            return new Chunk(candidates.size(), 0);
        }
        int marked = reservationRepository.markNoShow(locked);
        if (marked != locked.size()) {
            throw new IllegalStateException("잠근 예약과 바뀐 예약 수가 다름 locked=" + locked.size() + " marked=" + marked);
        }
        jdbcTemplate.update("""
                INSERT INTO reservation_history (reservation_id, from_status, to_status, actor_id, changed_at)
                SELECT id, 'RESERVED', 'NO_SHOW', NULL, :changedAt FROM reservation WHERE id IN (:ids)""",
                new MapSqlParameterSource().addValue("changedAt", LocalDateTime.now(clock)).addValue("ids", locked));
        return new Chunk(candidates.size(), marked);
    }

    @Transactional(readOnly = true)
    public List<Long> noShowMemberIds(LocalDate saleDate) {
        return reservationRepository.findNoShowMemberIds(saleDate);
    }

    /** @return 제한이 새로 걸리거나 늘어난 회원 수 */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public int applyBlocks(List<Long> memberIds, LocalDate today) {
        List<Long> repeated = reservationRepository.findRepeatNoShowMemberIds(memberIds, NoShowRule.windowStart(today),
                today, NoShowRule.BLOCK_THRESHOLD);
        return repeated.isEmpty() ? 0
                : memberRepository.extendReservationBlock(repeated, NoShowRule.blockedUntil(today));
    }
}
