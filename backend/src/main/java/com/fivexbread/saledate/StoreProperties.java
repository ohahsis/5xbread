package com.fivexbread.saledate;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import java.time.LocalTime;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 매장 영업시간 (부록 B: 화~일 08:00~21:00). 픽업 시각은 openTime 이상, closeTime 미만.
 * 부록 B 는 "사장님"이 바꾸는 값이지만 설정 화면이 없어 1차는 설정 파일로 둔다.
 */
@Validated
@ConfigurationProperties("fivexbread.store")
public record StoreProperties(@NotNull LocalTime openTime, @NotNull LocalTime closeTime) {

    @AssertTrue(message = "영업 시작은 종료보다 빨라야 합니다")
    boolean isOpenBeforeClose() {
        return openTime == null || closeTime == null || openTime.isBefore(closeTime);
    }
}
