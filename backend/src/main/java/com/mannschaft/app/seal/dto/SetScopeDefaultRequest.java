package com.mannschaft.app.seal.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * スコープデフォルト設定リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class SetScopeDefaultRequest {

    @NotNull
    private final String scopeType;

    private final Long scopeId;

    @NotNull
    private final Long sealId;
}
