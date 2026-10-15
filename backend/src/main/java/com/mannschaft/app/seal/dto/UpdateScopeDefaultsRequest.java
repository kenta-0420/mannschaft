package com.mannschaft.app.seal.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * スコープデフォルト一括更新リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class UpdateScopeDefaultsRequest {

    @NotNull
    @Valid
    private final List<@Valid ScopeDefaultItem> defaults;

    /**
     * スコープデフォルト更新アイテム。
     * variant から sealId を自動解決するため、sealId は不要。
     */
    @Getter
    @RequiredArgsConstructor
    public static class ScopeDefaultItem {

        @NotNull
        private final String scopeType;

        /** TEAM/ORGANIZATION のスコープID。DEFAULT の場合は null。 */
        private final Long scopeId;

        /** LAST_NAME / FULL_NAME / FIRST_NAME */
        @NotNull
        private final String variant;
    }
}
