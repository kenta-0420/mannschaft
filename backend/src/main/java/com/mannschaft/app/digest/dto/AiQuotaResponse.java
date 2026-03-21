package com.mannschaft.app.digest.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * AI 生成枠の使用状況レスポンス。
 */
@Getter
@RequiredArgsConstructor
public class AiQuotaResponse {

    private final boolean enabled;
    private final long used;
    private final int limit;
    private final long remaining;
}
