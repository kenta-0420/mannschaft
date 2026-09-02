package com.mannschaft.app.receipt;

import com.mannschaft.app.common.storage.R2UrlService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 本番環境用の領収書ロゴ URL 生成実装（F08.4 D-8）。
 *
 * <p>署名寿命は {@code R2UrlService} が設定値
 * {@code mannschaft.storage.presigned-download-ttl} から導出する。</p>
 */
@Component
@Profile("prod")
@RequiredArgsConstructor
public class R2ReceiptLogoUrlProvider implements ReceiptLogoUrlProvider {

    private final R2UrlService r2UrlService;

    @Override
    public String generateLogoUrl(String storageKey) {
        if (storageKey == null || storageKey.isBlank()) {
            return null;
        }
        return r2UrlService.generateSignedUrl(storageKey);
    }
}
