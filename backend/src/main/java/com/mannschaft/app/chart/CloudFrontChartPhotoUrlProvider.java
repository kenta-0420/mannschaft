package com.mannschaft.app.chart;

import com.mannschaft.app.common.storage.CloudFrontUrlService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 本番環境用のCloudFront署名付きURL生成実装。
 */
@Component
@Profile("prod")
@RequiredArgsConstructor
public class CloudFrontChartPhotoUrlProvider implements ChartPhotoUrlProvider {

    private static final int TTL_MINUTES = 15;

    private final CloudFrontUrlService cloudFrontUrlService;

    @Override
    public String generateSignedUrl(String s3Key) {
        return cloudFrontUrlService.generateSignedUrl(s3Key);
    }

    @Override
    public LocalDateTime getExpiresAt() {
        return LocalDateTime.now().plusMinutes(TTL_MINUTES);
    }
}
