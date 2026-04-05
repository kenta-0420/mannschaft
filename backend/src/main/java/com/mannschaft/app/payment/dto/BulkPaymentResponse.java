package com.mannschaft.app.payment.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * 一括支払い記録レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class BulkPaymentResponse {

    private final int createdCount;
    private final int skippedCount;
    private final List<SkippedEntry> skipped;

    /**
     * スキップされたエントリ情報。
     */
    @Getter
    @RequiredArgsConstructor
    public static class SkippedEntry {
        private final Long userId;
        private final String reason;
    }
}
