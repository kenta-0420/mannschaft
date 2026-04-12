package com.mannschaft.app.chart;

import com.mannschaft.app.common.storage.R2UrlService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 本番環境用の R2 / Cloudflare Workers URL 生成実装。
 */
@Component
@Profile("prod")
@RequiredArgsConstructor
public class R2ChartPhotoUrlProvider implements ChartPhotoUrlProvider {

    private static final int TTL_MINUTES = 15;

    private final R2UrlService r2UrlService;

    @Override
    public String generateSignedUrl(String r2Key) {
        return r2UrlService.generateSignedUrl(r2Key);
    }

    @Override
    public LocalDateTime getExpiresAt() {
        return LocalDateTime.now().plusMinutes(TTL_MINUTES);
    }
}
