package com.fivexbread.domain.reservation;

import com.fivexbread.common.BusinessException;
import com.fivexbread.common.ErrorCode;
import com.fivexbread.domain.reservation.model.Reservation;
import com.fivexbread.domain.reservation.model.enums.ReservationStatus;
import com.fivexbread.domain.reservation.model.vo.ReservationCursor;
import com.fivexbread.domain.reservation.model.vo.ReservationDetail;
import com.fivexbread.domain.reservation.model.vo.ReservationItemRow;
import com.fivexbread.domain.reservation.model.vo.ReservationLine;
import com.fivexbread.domain.reservation.model.vo.ReservationPage;
import com.fivexbread.domain.reservation.model.vo.ReservationResult;
import com.fivexbread.domain.reservation.repository.ReservationListRepository;
import com.fivexbread.domain.reservation.repository.ReservationRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 내 예약 목록·상세 (C-05, 13-7). 쿼리는 예약 본체 1번 + 항목 IN 1번.
 * 컬렉션 fetch join 은 행이 항목 수만큼 불어나 LIMIT 을 DB 에서 걸 수 없어 쓰지 않는다.
 */
@Service
@RequiredArgsConstructor
public class ReservationQueryService {

    /** 커밋 여부 확인 조회의 트랜잭션 시간 제한 (decisions.md 6장 "예약 DB 실패 뒤 보상 판단") */
    static final int COMMIT_CHECK_TIMEOUT_SECONDS = 2;

    private final ReservationListRepository reservationListRepository;
    private final ReservationRepository reservationRepository;
    private final Clock clock;

    /** size + 1 개를 읽어 다음 페이지가 있는지 판단한다 */
    @Transactional(readOnly = true)
    public ReservationPage list(long memberId, ReservationStatus status, ReservationCursor cursor, int size) {
        List<Reservation> rows = reservationListRepository.find(memberId, status, cursor, size + 1);
        boolean hasNext = rows.size() > size;
        List<Reservation> page = hasNext ? rows.subList(0, size) : rows;
        Map<Long, List<ReservationLine>> items = items(page);
        LocalDateTime now = LocalDateTime.now(clock);
        List<ReservationDetail> details = page.stream()
                .map(reservation -> ReservationDetail.of(reservation,
                        items.getOrDefault(reservation.getId(), List.of()), now))
                .toList();
        String nextCursor = hasNext ? ReservationCursor.of(page.getLast()).encode() : null;
        return new ReservationPage(details, nextCursor, hasNext);
    }

    /** 남의 예약과 없는 예약을 구분하지 않고 404 */
    @Transactional(readOnly = true)
    public ReservationDetail detail(long reservationId, long memberId) {
        Reservation reservation = reservationRepository.findByIdAndMemberId(reservationId, memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        return ReservationDetail.of(reservation,
                items(List.of(reservation)).getOrDefault(reservationId, List.of()), LocalDateTime.now(clock));
    }

    /**
     * Idempotency-Key 로 이미 커밋된 예약을 예약 생성 응답(201) 모양으로 (ReservationFacade 의 DB 실패 뒤 확인).
     * 항목·이름·시각을 모두 DB 에서 다시 읽어, 같은 키 재요청이 받는 저장된 응답과 똑같다. 남의 키면 없는 것으로 본다.
     * 시간 제한 2초: 이 조회는 DB 가 느리거나 끊긴 직후에 불린다. 기본(8초)만큼 기다리지 않고, 못 읽으면 Facade 가 보상한다.
     * 풀에서 커넥션을 기다리는 시간(Hikari 10초)은 이 제한에 들어가지 않는다.
     */
    @Transactional(readOnly = true, timeout = COMMIT_CHECK_TIMEOUT_SECONDS)
    public Optional<ReservationResult> findCreated(long memberId, String idempotencyKey) {
        return reservationRepository.findByIdempotencyKey(idempotencyKey)
                .filter(reservation -> reservation.getMemberId() == memberId)
                .map(reservation -> ReservationResult.of(reservation,
                        items(List.of(reservation)).getOrDefault(reservation.getId(), List.of())));
    }

    private Map<Long, List<ReservationLine>> items(List<Reservation> reservations) {
        if (reservations.isEmpty()) {
            return Map.of();
        }
        return reservationRepository.findItems(reservations.stream().map(Reservation::getId).toList()).stream()
                .collect(Collectors.groupingBy(ReservationItemRow::getReservationId, Collectors.mapping(
                        row -> new ReservationLine(row.getBreadId(), row.getName(), row.getQuantity(), row.getUnitPrice()),
                        Collectors.toList())));
    }
}
