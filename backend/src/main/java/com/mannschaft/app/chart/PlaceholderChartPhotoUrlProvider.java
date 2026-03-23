package com.mannschaft.app.chart;

import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 開発環境用のプレースホルダーURL生成実装。
 * 本番環境ではCloudFront署名付きURL生成実装に差し替える。
 */
@Component
public class PlaceholderChartPhotoUrlProvider implements ChartPhotoUrlProvider {

    private static final String PLACEHOLDER_BASE_URL = "https://cdn.example.com/";
    private static final int TTL_MINUTES = 15;

    @Override
    public String generateSignedUrl(String s3Key) {
        // 本番環境では CloudFrontSignedUrlProvider に差し替え（@Profile("prod")）
        return PLACEHOLDER_BASE_URL + s3Key + "?signed=placeholder";
    }

    @Override
    public LocalDateTime getExpiresAt() {
        return LocalDateTime.now().plusMinutes(TTL_MINUTES);
    }
}
