package com.mannschaft.app.village.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/**
 * 所属村一覧の公開トグル切替リクエスト（F17.2 §9.3）。
 *
 * <p>{@code PATCH /api/v1/villages/{villageId}/memberships/me/profile-visibility} の body。
 * 本人が「この村への所属を所属村一覧に公開してよいか」を切り替える。</p>
 */
@Schema(description = "所属村一覧の公開トグル切替リクエスト")
public record ProfileVisibilityUpdateRequest(

        @Schema(description = "この村所属を所属村一覧に公開するか（true=公開/false=非公開）",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "profilePublic は必須です")
        Boolean profilePublic
) {
}
