package com.fivexbread.reservation;

import com.fivexbread.common.BusinessException;
import com.fivexbread.common.ErrorCode;
import com.fivexbread.reservation.ReservationResponse.ItemResponse;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
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

    private final ReservationListRepository reservationListRepository;
    private final ReservationRepository reservationRepository;
    private final Clock clock;

    /** size + 1 개를 읽어 다음 페이지가 있는지 판단한다 */
    @Transactional(readOnly = true)
    public ReservationListResponse list(long memberId, ReservationStatus status, ReservationCursor cursor, int size) {
        List<Reservation> rows = reservationListRepository.find(memberId, status, cursor, size + 1);
        boolean hasNext = rows.size() > size;
        List<Reservation> page = hasNext ? rows.subList(0, size) : rows;
        Map<Long, List<ItemResponse>> items = items(page);
        LocalDateTime now = LocalDateTime.now(clock);
        List<ReservationDetailResponse> responses = page.stream()
                .map(reservation -> ReservationDetailResponse.of(reservation,
                        items.getOrDefault(reservation.getId(), List.of()), now))
                .toList();
        String nextCursor = hasNext ? ReservationCursor.of(page.getLast()).encode() : null;
        return new ReservationListResponse(responses, nextCursor, hasNext);
    }

    /** 남의 예약과 없는 예약을 구분하지 않고 404 */
    @Transactional(readOnly = true)
    public ReservationDetailResponse detail(long reservationId, long memberId) {
        Reservation reservation = reservationRepository.findByIdAndMemberId(reservationId, memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        return ReservationDetailResponse.of(reservation,
                items(List.of(reservation)).getOrDefault(reservationId, List.of()), LocalDateTime.now(clock));
    }

    private Map<Long, List<ItemResponse>> items(List<Reservation> reservations) {
        if (reservations.isEmpty()) {
            return Map.of();
        }
        return reservationRepository.findItems(reservations.stream().map(Reservation::getId).toList()).stream()
                .collect(Collectors.groupingBy(ReservationItemRow::getReservationId, Collectors.mapping(
                        row -> new ItemResponse(row.getBreadId(), row.getName(), row.getQuantity(), row.getUnitPrice()),
                        Collectors.toList())));
    }
}
