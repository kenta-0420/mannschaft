package com.mannschaft.app.village.dto;

import com.mannschaft.app.village.entity.VillageCharterDrafterEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

import java.util.UUID;

/**
 * 村憲章の策定者レスポンス（F17.3・設計書 §5.5/§18.1）。
 *
 * <p>{@code displayName} は焼き付けた村ニックネーム（{@code nickname_snapshot}）。
 * <b>{@code userId} は応答に載せない</b>（G4・実名世界への横串リンクを作らない・§5.5）。</p>
 */
@Schema(name = "CharterDrafterResponse", description = "村憲章の策定者（村ニックネーム焼付・userId非露出・G4）")
@Builder
public record CharterDrafterResponse(

        @Schema(description = "策定者ID（UUID）", requiredMode = Schema.RequiredMode.REQUIRED)
        UUID id,

        @Schema(description = "表示名（焼き付けた村ニックネーム・実名ではない）",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String displayName,

        @Schema(description = "表示順（0始まり）", requiredMode = Schema.RequiredMode.REQUIRED)
        int sortOrder
) {

    /** Entity から DTO を生成する（{@code userId} は載せない・G4）。 */
    public static CharterDrafterResponse of(VillageCharterDrafterEntity entity) {
        return CharterDrafterResponse.builder()
                .id(entity.getId())
                .displayName(entity.getNicknameSnapshot())
                .sortOrder(entity.getSortOrder() == null ? 0 : entity.getSortOrder())
                .build();
    }
}
