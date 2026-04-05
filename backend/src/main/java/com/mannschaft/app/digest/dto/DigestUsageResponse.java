package com.mannschaft.app.digest.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * AI API 利用量統計レスポンス（SYSTEM_ADMIN 用）。
 */
@Getter
@RequiredArgsConstructor
public class DigestUsageResponse {

    private final String period;
    private final long totalDigests;
    private final Map<String, Long> byStatus;
    private final long totalInputTokens;
    private final long totalOutputTokens;
    private final long estimatedCostJpy;
    private final List<ScopeUsage> topScopes;

    /**
     * スコープ別の利用量。
     */
    @Getter
    @RequiredArgsConstructor
    public static class ScopeUsage {
        private final String scopeType;
        private final Long scopeId;
        private final String scopeName;
        private final long aiDigestCount;
        private final long templateDigestCount;
        private final long aiRemaining;
    }
}
