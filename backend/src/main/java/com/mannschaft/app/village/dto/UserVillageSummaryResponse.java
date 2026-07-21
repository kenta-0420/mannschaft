package com.mannschaft.app.village.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

/**
 * 所属村一覧（{@code GET /api/v1/users/{userId}/villages}）の1件（F17.2 §9.3）。
 *
 * <p>「どんな村に居るか」を辿る導線であり、村ごとのニックネーム（村内 identity）は
 * <strong>返さない</strong>（横串で晒すと村の匿名世界が破れる・§9.3・G4）。
 * 村名・村紋・カテゴリ・村ID までに留める。</p>
 */
@Schema(description = "所属村一覧の1件（村名・村紋・カテゴリ・村IDのみ。ニックネームは返さない・§9.3）")
public record UserVillageSummaryResponse(

        @Schema(description = "村ID（UUID）", requiredMode = Schema.RequiredMode.REQUIRED)
        UUID villageId,

        @Schema(description = "村名（表示用）", requiredMode = Schema.RequiredMode.REQUIRED)
        String villageName,

        @Schema(description = "村紋の署名付き URL（未設定なら null）")
        String villageMonshoUrl,

        @Schema(description = "村カテゴリ（未設定なら null）")
        String category
) {
}
