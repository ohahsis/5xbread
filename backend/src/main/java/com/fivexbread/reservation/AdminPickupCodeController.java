package com.fivexbread.reservation;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 사장님 QR 스캔 (A-06·C-07, 10장 관리 API). 검증 = "검증 결과" 자원 생성이라 201. 수령은 하지 않는다 */
@RestController
@RequestMapping("/api/v1/admin/pickup-code-verifications")
@RequiredArgsConstructor
public class AdminPickupCodeController {

    private final PickupCodeService pickupCodeService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AdminReservationResponse verify(@Valid @RequestBody PickupCodeVerificationRequest request) {
        return pickupCodeService.verify(request.code());
    }
}
