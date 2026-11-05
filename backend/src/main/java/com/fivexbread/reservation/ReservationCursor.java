package com.fivexbread.reservation;

import com.fivexbread.common.BusinessException;
import com.fivexbread.common.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 내 예약 목록의 위치 (13-7). 마지막으로 준 예약의 (saleDate, id) 를 "2026-10-10|1042" 로 묶어 Base64 로 내보낸다.
 * URL 쿼리에 그대로 넣도록 URL-safe·패딩 없음. 서버가 만드는 모양이 아니면 400 이다.
 * 서명은 하지 않는다. 값을 그럴듯하게 바꿔도 쿼리의 member_id 조건 때문에 본인 예약의 다른 위치만 보인다.
 */
record ReservationCursor(LocalDate saleDate, long id) {

    private static final Pattern FORMAT = Pattern.compile("([1-9]\\d{3}-\\d{2}-\\d{2})\\|([1-9]\\d{0,18})");

    static ReservationCursor of(Reservation last) {
        return new ReservationCursor(last.getSaleDate(), last.getId());
    }

    String encode() {
        byte[] plain = (saleDate + "|" + id).getBytes(StandardCharsets.UTF_8);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(plain);
    }

    /** @throws BusinessException 서버가 준 형식이 아니면 400 */
    static ReservationCursor decode(String value) {
        try {
            String plain = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
            Matcher matcher = FORMAT.matcher(plain);
            if (matcher.matches()) {
                ReservationCursor cursor = new ReservationCursor(LocalDate.parse(matcher.group(1)),
                        Long.parseLong(matcher.group(2)));
                if (cursor.encode().equals(value)) {          // 패딩을 붙이는 등 같은 값의 다른 표기도 거절
                    return cursor;
                }
            }
        } catch (IllegalArgumentException | DateTimeParseException e) {
            // Base64 가 아니거나, 없는 날짜이거나, id 가 long 범위를 넘음. 아래에서 같은 400 으로 응답한다
        }
        throw new BusinessException(ErrorCode.INVALID_REQUEST, "cursor 가 올바르지 않습니다");
    }
}
