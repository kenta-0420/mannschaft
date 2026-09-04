package com.mannschaft.app.receipt;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 開発・テスト環境用の領収書ロゴ URL 生成実装（F08.4 D-8）。
 * 本番環境では {@link R2ReceiptLogoUrlProvider} が使用される。
 */
@Component
@Profile("!prod")
public class PlaceholderReceiptLogoUrlProvider implements ReceiptLogoUrlProvider {

    private static final String PLACEHOLDER_BASE_URL = "https://cdn.example.com/";

    @Override
    public String generateLogoUrl(String storageKey) {
        if (storageKey == null || storageKey.isBlank()) {
            return null;
        }
        return PLACEHOLDER_BASE_URL + storageKey + "?signed=placeholder";
    }
}
