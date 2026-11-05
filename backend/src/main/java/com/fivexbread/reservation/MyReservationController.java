package com.fivexbread.reservation;

import com.fivexbread.auth.AuthMember;
import com.fivexbread.common.BusinessException;
import com.fivexbread.common.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 내 예약 목록·상세 (C-05, 10장 손님 API). 로그인 필수. 조회만 해서 Redis·보상이 없어 Facade 없이 Service 를 부른다 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class MyReservationController {

    private static final int MAX_SIZE = 100;                // 10장: size 최대 100

    private final ReservationQueryService reservationQueryService;

    /** status 가 enum 값이 아니거나 size 가 숫자가 아니면 Spring 이 400. 빈 값(?cursor=)은 없는 것과 같다 */
    @GetMapping("/members/me/reservations")
    public ReservationListResponse list(@AuthenticationPrincipal AuthMember member,
                                        @RequestParam(required = false) ReservationStatus status,
                                        @RequestParam(required = false) String cursor,
                                        @RequestParam(defaultValue = "20") int size) {
        if (size < 1 || size > MAX_SIZE) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "size 는 1 이상 " + MAX_SIZE + " 이하입니다");
        }
        ReservationCursor position = cursor == null || cursor.isEmpty() ? null : ReservationCursor.decode(cursor);
        return reservationQueryService.list(member.memberId(), status, position, size);
    }

    /** 남의 예약은 404 (존재 여부를 숨김). 사장님은 /admin/reservations/{id} 로 본다 */
    @GetMapping("/reservations/{reservationId}")
    public ReservationDetailResponse detail(@AuthenticationPrincipal AuthMember member,
                                            @PathVariable long reservationId) {
        return reservationQueryService.detail(reservationId, member.memberId());
    }
}
