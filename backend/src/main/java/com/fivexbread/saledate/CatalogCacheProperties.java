package com.fivexbread.saledate;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** 빵 목록 로컬 캐시 (부록 B: 30초, 설정 파일) */
@Validated
@ConfigurationProperties("fivexbread.catalog-cache")
public record CatalogCacheProperties(@NotNull Duration ttl) {
}
