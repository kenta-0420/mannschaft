package com.mannschaft.app.chart;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 開発環境用のプレースホルダー URL 生成実装。
 * 本番環境では R2ChartPhotoUrlProvider が使用される。
 */
@Component
@Profile("!prod")
public class PlaceholderChartPhotoUrlProvider implements ChartPhotoUrlProvider {

    private static final String PLACEHOLDER_BASE_URL = "https://cdn.example.com/";
    private static final int TTL_MINUTES = 15;

    @Override
    public String generateSignedUrl(String r2Key) {
        // 本番環境では R2ChartPhotoUrlProvider に差し替え（@Profile("prod")）
        return PLACEHOLDER_BASE_URL + r2Key + "?signed=placeholder";
    }

    @Override
    public LocalDateTime getExpiresAt() {
        return LocalDateTime.now().plusMinutes(TTL_MINUTES);
    }
}
