package com.mannschaft.app.seal.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * スコープデフォルトレスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class ScopeDefaultResponse {

    private final Long id;
    private final Long userId;
    private final String scopeType;
    private final Long scopeId;
    private final Long sealId;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;
}
