package com.mannschaft.app.village.dto;

import com.mannschaft.app.village.entity.VillageCharterArticleEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

import java.util.UUID;

/**
 * 村憲章の条レスポンス（F17.3・設計書 §18.1）。
 *
 * <p>{@code articleNumber} は永続列ではなく<b>導出値</b>（非削除条を {@code sort_order} 昇順に
 * 並べた {@code index+1}＝第 N 条・§6.1）。内部順序の {@code sort_order} は<b>非露出</b>。</p>
 */
@Schema(name = "CharterArticleResponse", description = "村憲章の条（自動採番・sortOrder非露出・§18.1）")
@Builder
public record CharterArticleResponse(

        @Schema(description = "条ID（UUID）", requiredMode = Schema.RequiredMode.REQUIRED)
        UUID id,

        @Schema(description = "導出された条番号（第N条・sort_order昇順のindex+1・§6.1）",
                requiredMode = Schema.RequiredMode.REQUIRED)
        int articleNumber,

        @Schema(description = "条文（必須）", requiredMode = Schema.RequiredMode.REQUIRED)
        String body,

        @Schema(description = "付則（任意・未設定時null）")
        String supplement,

        @Schema(description = "条単位の楽観ロックversion（層1・PUTに同送）",
                requiredMode = Schema.RequiredMode.REQUIRED)
        long version
) {

    /**
     * Entity と導出済み条番号から DTO を生成する。
     *
     * @param entity        条エンティティ
     * @param articleNumber 表示採番（第 N 条・呼出元が並び順から導出・§6.1）
     */
    public static CharterArticleResponse of(VillageCharterArticleEntity entity, int articleNumber) {
        return CharterArticleResponse.builder()
                .id(entity.getId())
                .articleNumber(articleNumber)
                .body(entity.getBody())
                .supplement(entity.getSupplement())
                .version(entity.getVersion() == null ? 0L : entity.getVersion())
                .build();
    }
}
