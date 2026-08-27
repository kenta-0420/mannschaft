package com.mannschaft.app.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 一般ユーザー向け公開フィーチャーフラグレスポンスDTO。
 *
 * <p>{@link FeatureFlagResponse} と異なり、{@code description} / {@code updatedBy} / {@code id} など
 * 管理者専用情報を一切含まない（Gate基盤工事①）。</p>
 */
@Schema(description = "公開フィーチャーフラグ")
public record PublicFeatureFlagResponse(

        @Schema(description = "フラグキー", example = "FEATURE_NEW_UI")
        String flagKey,

        @Schema(description = "有効フラグ", example = "true")
        boolean enabled) {
}
