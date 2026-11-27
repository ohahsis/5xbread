package com.fivexbread.inbound.controller.saledate;

import com.fivexbread.domain.saledate.StockReconciliationFacade;
import com.fivexbread.inbound.controller.saledate.model.response.StockReconciliationResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import com.fivexbread.domain.auth.model.vo.AuthMember;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 수량 재계산 (A-12). 동사 URL 대신 "재계산 기록" 자원을 만든다(10장). 기록을 다시 읽는 API 는 없어 Location 은 주지 않고
 * 본문에 기록 id 를 담는다.
 */
@RestController
@RequestMapping("/api/v1/admin/sale-dates/{date}/stock-reconciliations")
@RequiredArgsConstructor
public class AdminStockReconciliationController {

    private final StockReconciliationFacade reconciliationFacade;

    @PostMapping
    @ApiResponse(responseCode = "201", description = "재계산함")
    public ResponseEntity<StockReconciliationResponse> create(
            @AuthenticationPrincipal AuthMember admin,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ResponseEntity.status(HttpStatus.CREATED).body(StockReconciliationResponse.from(reconciliationFacade.reconcile(date, admin.memberId())));
    }
}
