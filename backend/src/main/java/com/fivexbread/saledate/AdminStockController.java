package com.fivexbread.saledate;

import com.fivexbread.common.BusinessException;
import com.fivexbread.common.ErrorCode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 판매일별 수량 (A-02, 13-8). 조회 응답의 ETag("v12")를 저장 요청의 If-Match 로 돌려받는 낙관적 동시성 제어.
 * If-Match 가 없으면 428, 형식이 다르거나 버전이 다르면 412. 약한 ETag(W/)는 If-Match 에서 강한 비교라 맞지 않는 것으로 본다.
 */
@RestController
@RequestMapping("/api/v1/admin/sale-dates/{date}/stocks")
@RequiredArgsConstructor
public class AdminStockController {

    private static final Pattern VERSION_TAG = Pattern.compile("^\"v(\\d{1,9})\"$");

    private final StockService stockService;
    private final StockFacade stockFacade;

    @GetMapping
    public ResponseEntity<StocksResponse> get(@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        StockService.Result result = stockService.get(date);
        return ResponseEntity.ok().eTag(result.eTag()).body(result.stocks());
    }

    @PutMapping
    public ResponseEntity<StocksResponse> put(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestBody @NotEmpty @Size(max = 100) List<@Valid StockRowRequest> rows) {
        StockService.Result result = stockFacade.put(date, expectedVersion(ifMatch), rows);
        return ResponseEntity.ok().eTag(result.eTag()).body(result.stocks());
    }

    private static int expectedVersion(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new BusinessException(ErrorCode.PRECONDITION_REQUIRED);
        }
        Matcher matcher = VERSION_TAG.matcher(ifMatch.strip());
        if (!matcher.matches()) {
            throw new BusinessException(ErrorCode.PRECONDITION_FAILED);
        }
        return Integer.parseInt(matcher.group(1));
    }
}
