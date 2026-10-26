package com.fivexbread.saledate;

import jakarta.validation.Valid;
import java.net.URI;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 판매일 설정 (A-04, 10장 관리 API). 날짜가 곧 자원 ID 라 PUT: 새로 만들면 201, 바꾸면 200 */
@RestController
@RequestMapping("/api/v1/admin/sale-dates")
@RequiredArgsConstructor
public class AdminSaleDateController {

    private final SaleDateService saleDateService;

    @PutMapping("/{date}")
    public ResponseEntity<SaleDateResponse> put(@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                                @Valid @RequestBody SaleDateRequest request) {
        SaleDateService.Result result = saleDateService.put(date, request);
        if (result.created()) {
            return ResponseEntity.created(URI.create("/api/v1/admin/sale-dates/" + date)).body(result.saleDate());
        }
        return ResponseEntity.status(HttpStatus.OK).body(result.saleDate());
    }
}
