package com.mannschaft.app.village.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 条の本文/付則を更新するリクエスト（{@code PUT .../charter/articles/{id}}・F17.3・設計書 §18.2）。
 *
 * <p><b>{@code version} を同送する</b>（条単位 {@code @Version} の層1 楽観ロック・§7）。
 * 古い version なら {@code CHARTER_ARTICLE_VERSION_CONFLICT}（409）。</p>
 */
public record CharterArticleUpdateRequest(

        @Schema(description = "条文（必須・最大2000字）", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank @Size(max = 2000) String body,

        @Schema(description = "付則（任意・最大2000字）")
        @Size(max = 2000) String supplement,

        @Schema(description = "条単位の楽観ロックversion（層1・必須）", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull Long version
) {
}
