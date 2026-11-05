package com.fivexbread.reservation;

import static com.fivexbread.common.ClockConfig.KST;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Base64;
import java.util.Optional;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * 픽업 QR 토큰 (C-07, 13-9 1단계): "예약번호.만료시각.서명". 숨기기가 아니라 바꾼 것을 알아채는 것이 목적이라 암호화가 아닌 서명.
 * - 만료시각: 판매일 다음 날 00:00 KST 의 epoch 초 (부록 B: QR 만료). 그 시각부터는 무효
 * - 서명: HMAC-SHA256("예약번호.만료시각"), Base64 URL-safe, 패딩 없음
 *
 * 예약번호(MMDD-NNNN)에는 연도가 없다. 판매일 전체는 서명 대상인 만료시각에 들어 있다(만료일 − 1일).
 * 검증할 때 만료시각이 정확히 "어떤 날의 다음 날 00:00"인지, 예약번호의 MMDD 가 그 판매일과 같은지도 본다.
 * 그래서 작년 같은 예약번호의 토큰이나 다른 날로 바꾼 토큰은 서명이 맞지 않거나 이 검사에서 걸린다.
 *
 * 토큰과 키는 로그·예외 메시지에 넣지 않는다.
 */
@Component
class PickupCodes {

    private static final String ALGORITHM = "HmacSHA256";
    private static final int MIN_SECRET_BYTES = 32;
    private static final Pattern FORMAT = Pattern.compile("^(\\d{4}-\\d{4})\\.(\\d{1,12})\\.([A-Za-z0-9_-]{43})$");

    private final SecretKeySpec key;

    PickupCodes(PickupCodeProperties properties) {
        byte[] secret = properties.secret().getBytes(StandardCharsets.UTF_8);
        if (secret.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException("PICKUP_SECRET 은 UTF-8 " + MIN_SECRET_BYTES + "바이트 이상이어야 합니다");
        }
        this.key = new SecretKeySpec(secret, ALGORITHM);
    }

    /** @param expiresAt 이 시각부터 무효 */
    record Issued(String code, Instant expiresAt) {
    }

    /** 서명·만료를 통과한 토큰이 가리키는 예약 */
    record Verified(LocalDate saleDate, String reservationNo) {
    }

    Issued issue(LocalDate saleDate, String reservationNo) {
        Instant expiresAt = expiresAt(saleDate);
        String payload = reservationNo + "." + expiresAt.getEpochSecond();
        return new Issued(payload + "." + sign(payload), expiresAt);
    }

    /** 형식 오류·서명 불일치·만료를 구분하지 않고 빈 값 (어느 것이 틀렸는지 알려주지 않는다) */
    Optional<Verified> verify(String code, Instant now) {
        var matcher = FORMAT.matcher(code == null ? "" : code);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        String reservationNo = matcher.group(1);
        String payload = reservationNo + "." + matcher.group(2);
        // 상수 시간 비교: 앞에서부터 다른 글자를 만나면 멈추는 equals 는 걸린 시간으로 서명을 한 글자씩 맞혀 볼 수 있다
        if (!MessageDigest.isEqual(sign(payload).getBytes(StandardCharsets.US_ASCII),
                matcher.group(3).getBytes(StandardCharsets.US_ASCII))) {
            return Optional.empty();
        }
        Instant expiresAt = Instant.ofEpochSecond(Long.parseLong(matcher.group(2)));
        if (!now.isBefore(expiresAt)) {
            return Optional.empty();
        }
        LocalDate saleDate = expiresAt.atZone(KST).toLocalDate().minusDays(1);
        if (!expiresAt.equals(expiresAt(saleDate))
                || !reservationNo.startsWith("%02d%02d-".formatted(saleDate.getMonthValue(), saleDate.getDayOfMonth()))) {
            return Optional.empty();
        }
        return Optional.of(new Verified(saleDate, reservationNo));
    }

    private static Instant expiresAt(LocalDate saleDate) {
        return saleDate.plusDays(1).atStartOfDay(KST).toInstant();
    }

    private String sign(String payload) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);           // Mac 은 스레드 안전하지 않아 호출마다 만든다
            mac.init(key);
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 을 쓸 수 없음", e);
        }
    }
}
