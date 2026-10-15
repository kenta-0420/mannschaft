package com.mannschaft.app.village.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/**
 * 策定者を追加するリクエスト（{@code POST .../charter/drafters}・F17.3・設計書 §18.2）。
 *
 * <p>{@code userId} のみ受け取り、村ニックネームはサーバが解決して {@code nickname_snapshot} に
 * 焼き付ける（§5.2）。同一ユーザーの二重登録は {@code CHARTER_DRAFTER_DUPLICATE}（409・§5.4）。</p>
 */
public record CharterDrafterCreateRequest(

        @Schema(description = "策定者に加えるユーザーID（村ニックネームはサーバが焼付）",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull Long userId
) {
}
