package com.mannschaft.app.receipt.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 一括無効化結果レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class BulkVoidResultResponse {
    private final Integer voidedCount;
    private final Integer skippedCount;
}
