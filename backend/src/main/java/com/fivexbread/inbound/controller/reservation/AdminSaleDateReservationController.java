package com.fivexbread.inbound.controller.reservation;

import com.fivexbread.common.BusinessException;
import com.fivexbread.common.ErrorCode;
import com.fivexbread.domain.reservation.AdminReservationQueryService;
import com.fivexbread.domain.reservation.PickupSummaryQueryService;
import com.fivexbread.domain.reservation.model.enums.ReservationStatus;
import com.fivexbread.domain.reservation.model.vo.AdminReservationCursor;
import com.fivexbread.inbound.controller.reservation.model.response.AdminReservationListResponse;
import com.fivexbread.inbound.controller.reservation.model.response.PickupSummaryResponse;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 픽업 준비 (A-05, M-07). ADMIN 만(SecurityConfig /api/v1/admin/**).
 * 정의서 11장은 수령 검색과 같은 /admin/reservations?saleDate= 를 쓰지만, 판매일 하위 자원으로 나눴다 (decisions.md 6장 "A-05 판매일 예약 목록").
 */
@RestController
@RequestMapping("/api/v1/admin/sale-dates/{saleDate}")
@RequiredArgsConstructor
public class AdminSaleDateReservationController {

    static final int DEFAULT_SIZE = 100;
    static final int MAX_SIZE = 200;                       // 인쇄 화면이 끝까지 넘겨 읽는다. 한 번에 너무 크지 않게

    private final AdminReservationQueryService queryService;
    private final PickupSummaryQueryService pickupSummaryQueryService;

    /** 픽업 시각 → 예약번호 순. status·pickupSlotId 를 비우면 전체. 빈 값(?cursor=)은 없는 것과 같다 */
    @GetMapping("/reservations")
    public AdminReservationListResponse list(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate saleDate,
            @RequestParam(required = false) ReservationStatus status,
            @RequestParam(required = false) Long pickupSlotId,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "" + DEFAULT_SIZE) int size) {
        if (size < 1 || size > MAX_SIZE) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "size 는 1 이상 " + MAX_SIZE + " 이하입니다");
        }
        AdminReservationCursor position = cursor == null || cursor.isEmpty() ? null : AdminReservationCursor.decode(cursor);
        return AdminReservationListResponse.from(queryService.list(saleDate, status, pickupSlotId, position, size));
    }

    /** 시간대별·빵별 포장 수량. status 기본은 RESERVED(아직 받아 가지 않은 것) */
    @GetMapping("/pickup-summary")
    public PickupSummaryResponse pickupSummary(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate saleDate,
            @RequestParam(defaultValue = "RESERVED") ReservationStatus status) {
        return PickupSummaryResponse.from(pickupSummaryQueryService.summary(saleDate, status));
    }
}
