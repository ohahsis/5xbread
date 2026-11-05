package com.fivexbread.reservation;

import com.fivexbread.auth.AuthMember;
import com.fivexbread.common.BusinessException;
import com.fivexbread.common.ErrorCode;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 예약 생성·취소·픽업 QR (C-04·C-06·C-07, 10장 손님 API). 로그인 필수 */
@RestController
@RequestMapping("/api/v1/reservations")
@RequiredArgsConstructor
public class ReservationController {

    static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    private static final Pattern UUID_FORMAT =
            Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    static final String MERGE_PATCH_JSON = "application/merge-patch+json";   // RFC 7396

    private final ReservationFacade reservationFacade;
    private final ReservationCancelFacade reservationCancelFacade;
    private final PickupCodeService pickupCodeService;

    /** 형식 검증(@Valid 400) → Idempotency-Key 없음 428·형식 400 → Facade. 같은 키 재요청도 처음과 같은 201 */
    @PostMapping
    public ResponseEntity<Object> create(@AuthenticationPrincipal AuthMember member,
                                         @RequestHeader(name = IDEMPOTENCY_KEY, required = false) String idempotencyKey,
                                         @Valid @RequestBody ReservationCreateRequest request) {
        ReservationFacade.Created created = reservationFacade.create(member.memberId(), key(idempotencyKey), request);
        return ResponseEntity.created(URI.create(created.location())).body(created.body());
    }

    /** 손님 취소 (C-06, 13-2). 본문은 {"status":"CANCELED"}만. 남의 예약은 404 */
    @PatchMapping(value = "/{reservationId}", consumes = MERGE_PATCH_JSON)
    public ReservationResponse patch(@AuthenticationPrincipal AuthMember member, @PathVariable long reservationId,
                                     @RequestBody Map<String, Object> body) {
        ReservationPatch.customer(body);                    // 형식 검증만. 손님은 CANCELED 하나뿐
        return reservationCancelFacade.cancel(reservationId, ReservationActor.customer(member.memberId()), null);
    }

    /** 픽업 QR 서명 토큰 (C-07, 13-9 1단계). 본인 예약만, 남의 예약은 404 */
    @GetMapping("/{reservationId}/pickup-code")
    public PickupCodeResponse pickupCode(@AuthenticationPrincipal AuthMember member, @PathVariable long reservationId) {
        return pickupCodeService.issue(reservationId, member.memberId());
    }

    private static String key(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REQUIRED);
        }
        String key = idempotencyKey.strip();
        if (!UUID_FORMAT.matcher(key).matches()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, IDEMPOTENCY_KEY + " 는 UUID 형식이어야 합니다");
        }
        return key.toLowerCase(Locale.ROOT);
    }
}
