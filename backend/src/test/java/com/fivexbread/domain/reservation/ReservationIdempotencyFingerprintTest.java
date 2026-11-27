package com.fivexbread.domain.reservation;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fivexbread.inbound.controller.reservation.model.request.ReservationCreateRequest;
import com.fivexbread.support.IntegrationTestSupport;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Idempotency 지문이 배포를 넘어 같아야 한다. 바뀌면 배포 중(옛 앱이 저장한 키를 새 앱이 읽음) 같은 요청의 재시도가
 * 422 IDEMPOTENCY_KEY_MISMATCH 가 된다. 지문은 예전부터 SHA-256(memberId + ":" + 요청 본문 JSON)이다.
 */
class ReservationIdempotencyFingerprintTest extends IntegrationTestSupport {

    @Autowired
    ReservationIdempotency idempotency;

    @Autowired
    ObjectMapper objectMapper;

    private static final ReservationCreateRequest REQUEST = new ReservationCreateRequest(LocalDate.of(2026, 12, 8), 7L,
            List.of(new ReservationCreateRequest.Item(3L, 2), new ReservationCreateRequest.Item(1L, 1)));

    /** 지문을 ReservationCreateRequest 로 직접 만들던 코드(#32 시점)에서 뽑은 값 */
    @Test
    void 같은_요청이면_예전에_뽑아_둔_지문과_같다() {
        assertThat(idempotency.fingerprint(42L, REQUEST.toCommand()))
                .isEqualTo("41cf10862652a24dc7110c24a9b1197995e69a4dbbb93d0d1b060b1a8a09ee35");
    }

    @Test
    void 지문은_요청_본문_JSON의_해시와_같다() throws Exception {
        String source = 42L + ":" + objectMapper.writeValueAsString(REQUEST);
        String expected = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8)));

        assertThat(idempotency.fingerprint(42L, REQUEST.toCommand())).isEqualTo(expected);
    }
}
