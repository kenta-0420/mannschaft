package com.mannschaft.app.village.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

/**
 * 条の並び順を一括更新するリクエスト（{@code PATCH .../charter/articles/order}・F17.3・設計書 §18.2）。
 *
 * <p>{@code articleIds} は当該 charter の非削除条の<b>完全集合</b>を並べ替えて送る（過不足・重複は 400・AC-13）。
 * <b>{@code charterVersion} を同送する</b>（親 charter {@code @Version} の層2 楽観ロック・§7）。
 * 親行の悲観ロック取得<b>後</b>に楽観一致検査を行い、不一致なら
 * {@code CHARTER_ORDER_VERSION_CONFLICT}（409）。</p>
 */
public record CharterArticleOrderUpdateRequest(

        @Schema(description = "非削除条の完全集合を並べ替えたid列（過不足・重複は400）",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull @NotEmpty List<UUID> articleIds,

        @Schema(description = "親charterの楽観ロックversion（層2・必須）", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull Long charterVersion
) {
}
