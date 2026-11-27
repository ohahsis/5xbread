package com.fivexbread.domain.saledate.model.vo;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

/**
 * 판매일 설정 입력 (A-04). 시각은 KST 로 바꾼 값 (decisions.md 3장)
 *
 * @param pickupTimes 픽업 시각 목록. 휴무일이면 빈 목록
 */
public record SaleDateCommand(LocalDateTime openAt, LocalDateTime closeAt, boolean holiday, List<LocalTime> pickupTimes) {
}
