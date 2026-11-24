package com.fivexbread.domain.member;

import com.fivexbread.config.WithdrawalProperties;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * 탈퇴한 번호의 해시 (AU-07, member.withdrawn_phone_hash CHAR(64)). 번호 원문은 지우고 재가입 제한 기간 동안 이 값만 남긴다.
 * HMAC-SHA256(키, 숫자 11자리) 의 소문자 hex 64자. 같은 번호는 같은 값이라 가입 때 비교할 수 있고, 키가 없으면 되돌릴 수 없다.
 */
@Component
public class WithdrawnPhoneHasher {

    private static final String ALGORITHM = "HmacSHA256";
    private static final int MIN_SECRET_BYTES = 32;

    private final SecretKeySpec key;

    WithdrawnPhoneHasher(WithdrawalProperties properties) {
        byte[] secret = properties.phoneHashSecret().getBytes(StandardCharsets.UTF_8);
        if (secret.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException("WITHDRAWN_PHONE_SECRET 은 UTF-8 " + MIN_SECRET_BYTES + "바이트 이상이어야 합니다");
        }
        this.key = new SecretKeySpec(secret, ALGORITHM);
    }

    /** @param phone 숫자 11자리로 정규화된 번호 */
    public String hash(String phone) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);           // Mac 은 스레드 안전하지 않아 호출마다 만든다
            mac.init(key);
            return HexFormat.of().formatHex(mac.doFinal(phone.getBytes(StandardCharsets.US_ASCII)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 을 쓸 수 없음", e);
        }
    }
}
