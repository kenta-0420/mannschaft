package com.mannschaft.app.village.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 村憲章の全体レスポンス（{@code GET .../charter} 本体・F17.3・設計書 §18.1）。
 *
 * <p>憲章メタ＋条一覧（自動採番済み）＋策定者＋改定履歴をまとめて返す。未制定の村では
 * {@code hasCharter=false}・{@code enactedAt=null}・各配列は空（404 にしない・§12.2）。
 * 構造変更系 EP の応答もこの全体 DTO に統一し、FE が version・採番・策定者連番を一括で
 * 載せ替えられるようにする（§18.2）。</p>
 */
@Schema(name = "VillageCharterResponse", description = "村憲章の全体（メタ＋条＋策定者＋改定履歴・§18.1）")
@Builder
public record VillageCharterResponse(

        @Schema(description = "村ID（UUID）", requiredMode = Schema.RequiredMode.REQUIRED)
        UUID villageId,

        @Schema(description = "憲章が制定済みか（未制定はfalse・§12.2）",
                requiredMode = Schema.RequiredMode.REQUIRED)
        boolean hasCharter,

        @Schema(description = "制定日（未制定はnull）")
        LocalDateTime enactedAt,

        @Schema(description = "改定日（未改正はnull）")
        LocalDateTime lastRevisedAt,

        @Schema(description = "親charterの楽観ロックversion（層2）。PATCH orderにcharterVersionとして同送。"
                + "POST/DELETEには同送不要（悲観ロック直列化・§6.3）。未制定はnull")
        Long version,

        @Schema(description = "閲覧者が現役HEADMAN/ELDERか（FEの編集UI出し分け）",
                requiredMode = Schema.RequiredMode.REQUIRED)
        boolean canEdit,

        @Schema(description = "条一覧（自動採番済み・articleNumber昇順）",
                requiredMode = Schema.RequiredMode.REQUIRED)
        List<CharterArticleResponse> articles,

        @Schema(description = "策定者一覧（sortOrder昇順）", requiredMode = Schema.RequiredMode.REQUIRED)
        List<CharterDrafterResponse> drafters,

        @Schema(description = "改定履歴（revisedAt降順）", requiredMode = Schema.RequiredMode.REQUIRED)
        List<CharterRevisionResponse> revisions
) {
}
