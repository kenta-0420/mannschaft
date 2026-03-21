package com.mannschaft.app.seal.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 管理者用一括再生成レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class AdminRegenerateResponse {

    private final Long totalProcessed;
    private final Long successCount;
    private final Long failureCount;
}
