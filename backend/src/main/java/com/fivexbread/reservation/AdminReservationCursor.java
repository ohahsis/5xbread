package com.fivexbread.reservation;

import com.fivexbread.common.BusinessException;
import com.fivexbread.common.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 판매일 예약 목록의 위치 (A-05). 마지막으로 준 예약의 (pickupAt, reservationNo) 를 "2026-10-10T11:00:00|1010-0427" 로 묶어
 * URL-safe Base64(패딩 없음)로 내보낸다. 판매일 안에서 예약번호가 유일하므로(uk_reservation_no) 이 둘로 위치가 하나로 정해진다.
 * 서버가 만드는 모양이 아니면 400. 서명은 하지 않는다: 사장님 전용 목록이라 위치를 바꿔도 볼 수 있는 범위가 같다.
 */
record AdminReservationCursor(LocalDateTime pickupAt, String reservationNo) {

    private static final DateTimeFormatter FORMAT_TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss");
    private static final Pattern FORMAT = Pattern.compile("(\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2})\\|(\\d{4}-\\d{4})");

    String encode() {
        byte[] plain = (FORMAT_TIME.format(pickupAt) + "|" + reservationNo).getBytes(StandardCharsets.UTF_8);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(plain);
    }

    /** @throws BusinessException 서버가 준 형식이 아니면 400 */
    static AdminReservationCursor decode(String value) {
        try {
            String plain = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
            Matcher matcher = FORMAT.matcher(plain);
            if (matcher.matches()) {
                AdminReservationCursor cursor = new AdminReservationCursor(
                        LocalDateTime.parse(matcher.group(1), FORMAT_TIME), matcher.group(2));
                if (cursor.encode().equals(value)) {          // 같은 값의 다른 표기(패딩 등)도 거절
                    return cursor;
                }
            }
        } catch (IllegalArgumentException | DateTimeParseException e) {
            // Base64 가 아니거나 없는 시각. 아래에서 같은 400 으로 응답한다
        }
        throw new BusinessException(ErrorCode.INVALID_REQUEST, "cursor 가 올바르지 않습니다");
    }
}
