package com.mannschaft.app.village.dto;

import com.mannschaft.app.village.entity.VillageCharterRevisionEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 村憲章の改定履歴レスポンス（F17.3・設計書 §8.4/§18.1）。
 *
 * <p>軽量履歴（条文スナップショット無し）。{@code revised_at} 降順で返す（新しい改定が先頭・§8.4）。</p>
 */
@Schema(name = "CharterRevisionResponse", description = "村憲章の改定履歴（日付＋任意メモの軽量履歴・§8.3）")
@Builder
public record CharterRevisionResponse(

        @Schema(description = "改定履歴ID（UUID）", requiredMode = Schema.RequiredMode.REQUIRED)
        UUID id,

        @Schema(description = "改定日時（「改正を確定」時刻）", requiredMode = Schema.RequiredMode.REQUIRED)
        LocalDateTime revisedAt,

        @Schema(description = "改定メモ（任意・未設定時null）")
        String note
) {

    /** Entity から DTO を生成する。 */
    public static CharterRevisionResponse of(VillageCharterRevisionEntity entity) {
        return CharterRevisionResponse.builder()
                .id(entity.getId())
                .revisedAt(entity.getRevisedAt())
                .note(entity.getNote())
                .build();
    }
}
