package com.mannschaft.app.village.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

/**
 * 所属村一覧の公開トグル切替結果（F17.2 §9.3）。
 */
@Schema(description = "所属村一覧の公開トグル切替結果")
public record ProfileVisibilityResponse(

        @Schema(description = "対象の村ID", requiredMode = Schema.RequiredMode.REQUIRED)
        UUID villageId,

        @Schema(description = "切替後の公開状態", requiredMode = Schema.RequiredMode.REQUIRED)
        boolean profilePublic
) {
}
