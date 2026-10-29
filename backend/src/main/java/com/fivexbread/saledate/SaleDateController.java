package com.fivexbread.saledate;

import com.fivexbread.auth.AuthMember;
import com.fivexbread.common.BusinessException;
import com.fivexbread.common.ErrorCode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 손님용 판매일 조회 (C-02, 10장 손님 API). 모두 공개라 로그인하지 않아도 200 (SecurityConfig).
 * 날짜 형식이 틀리면 400, 판매일이 없으면 404 (13-4 1단계).
 */
@RestController
@RequestMapping("/api/v1/sale-dates")
@RequiredArgsConstructor
public class SaleDateController {

    static final int MAX_RANGE_DAYS = 31;                   // 10장: 기간 최대 31일 (from·to 포함)

    private final SaleDateQueryService saleDateQueryService;

    @GetMapping
    public List<SaleDateStatusResponse> list(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                             @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        if (to.isBefore(from) || ChronoUnit.DAYS.between(from, to) >= MAX_RANGE_DAYS) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "조회 기간은 from 부터 to 까지 최대 " + MAX_RANGE_DAYS + "일입니다");
        }
        return saleDateQueryService.list(from, to);
    }

    @GetMapping("/{date}")
    public SaleDateStatusResponse get(@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return saleDateQueryService.get(date);
    }

    /** 잔여는 수시로, 내 예약 수량은 사람마다 달라 공유 캐시에 남지 않게 no-store (13-4 7단계) */
    @GetMapping("/{date}/breads")
    public ResponseEntity<SaleBreadsResponse> breads(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @AuthenticationPrincipal AuthMember member) {
        Long memberId = member == null ? null : member.memberId();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(saleDateQueryService.breads(date, memberId));
    }

    @GetMapping("/{date}/pickup-slots")
    public List<PickupSlotResponse> pickupSlots(@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return saleDateQueryService.pickupSlots(date);
    }
}
