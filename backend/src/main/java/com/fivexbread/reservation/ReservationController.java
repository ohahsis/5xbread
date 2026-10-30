package com.fivexbread.reservation;

import com.fivexbread.auth.AuthMember;
import com.fivexbread.common.BusinessException;
import com.fivexbread.common.ErrorCode;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.Locale;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 예약 (C-04, 10장 손님 API). 로그인 필수 */
@RestController
@RequestMapping("/api/v1/reservations")
@RequiredArgsConstructor
public class ReservationController {

    static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    private static final Pattern UUID_FORMAT =
            Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private final ReservationFacade reservationFacade;

    /** 형식 검증(@Valid 400) → Idempotency-Key 없음 428·형식 400 → Facade. 같은 키 재요청도 처음과 같은 201 */
    @PostMapping
    public ResponseEntity<Object> create(@AuthenticationPrincipal AuthMember member,
                                         @RequestHeader(name = IDEMPOTENCY_KEY, required = false) String idempotencyKey,
                                         @Valid @RequestBody ReservationCreateRequest request) {
        ReservationFacade.Created created = reservationFacade.create(member.memberId(), key(idempotencyKey), request);
        return ResponseEntity.created(URI.create(created.location())).body(created.body());
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
