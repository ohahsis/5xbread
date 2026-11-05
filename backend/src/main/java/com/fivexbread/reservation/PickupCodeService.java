package com.fivexbread.reservation;

import static com.fivexbread.common.ClockConfig.KST;

import com.fivexbread.common.BusinessException;
import com.fivexbread.common.ErrorCode;
import com.fivexbread.reservation.PickupCodes.Issued;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 픽업 QR 발급·검증 (C-07, 13-9 1·2단계). 검증은 예약 정보를 돌려줄 뿐 수령하지 않는다.
 * 사장님이 화면에서 확인한 뒤 PATCH {"status":"PICKED_UP"} 으로 수령한다.
 */
@Service
@RequiredArgsConstructor
public class PickupCodeService {

    private final ReservationRepository reservationRepository;
    private final AdminReservationQueryService queryService;
    private final PickupCodes pickupCodes;
    private final Clock clock;

    /** 손님 본인 예약만. 남의 예약·없는 예약은 구분하지 않고 404 */
    @Transactional(readOnly = true)
    public PickupCodeResponse issue(long reservationId, long memberId) {
        Reservation reservation = reservationRepository.findByIdAndMemberId(reservationId, memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        Issued issued = pickupCodes.issue(reservation.getSaleDate(), reservation.getReservationNo());
        return new PickupCodeResponse(issued.code(), issued.expiresAt().atZone(KST).toOffsetDateTime());
    }

    /** 형식·서명·만료가 틀렸거나 그 예약이 없으면 모두 422 INVALID_PICKUP_CODE. 상태(이미 수령·취소)는 응답의 status 로 보여준다 */
    @Transactional(readOnly = true)
    public AdminReservationResponse verify(String code) {
        return pickupCodes.verify(code, clock.instant())
                .flatMap(verified -> queryService.find(verified.saleDate(), verified.reservationNo()))
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_PICKUP_CODE));
    }
}
