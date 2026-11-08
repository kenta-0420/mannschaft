package com.mannschaft.app.common.architecture.plaintexttoken;

import java.time.Duration;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * fixture（番人6 陰性対照・AC-28）: 本人のセッション内だけで使う一時キー。
 *
 * <p>本番の {@code BudgetCsvService}（CSV プレビュー キー）・{@code RepairPlanItemCsvService}（取込みトークン）と
 * 同じ形にしてある: コンパイル時定数の接頭辞＋{@code UUID.randomUUID()}＋{@code opsForValue().set}。
 * {@code BearerSecretValkeyKey.of} を呼ばないので、番人6の対象にならない（違反にも免除にも数えない）。</p>
 */
public class CsvPreviewLikeFixtureService {

    private static final String CSV_PREVIEW_PREFIX = "fixture:csv:preview:";

    private static final Duration PREVIEW_TTL = Duration.ofMinutes(30);

    public String preview(StringRedisTemplate redisTemplate, String csvContent) {
        String previewKey = UUID.randomUUID().toString();
        redisTemplate.opsForValue().set(CSV_PREVIEW_PREFIX + previewKey, csvContent, PREVIEW_TTL);
        return previewKey;
    }
}
