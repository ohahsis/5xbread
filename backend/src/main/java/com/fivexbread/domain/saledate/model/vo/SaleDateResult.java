package com.fivexbread.domain.saledate.model.vo;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

/**
 * 저장한 판매일 설정 (A-04)
 *
 * @param pickupTimes 픽업 시각, 이른 순
 * @param created     새로 만들었으면 true (201), 있던 것을 바꿨으면 false (200)
 */
public record SaleDateResult(LocalDate saleDate, LocalDateTime openAt, LocalDateTime closeAt, boolean holiday,
                             List<LocalTime> pickupTimes, boolean created) {
}
