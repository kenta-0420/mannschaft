package com.mannschaft.app.billing.beta.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * F20.3 ベータ特典: シスアド 取消リクエスト（設計書 02 §4.2）。
 *
 * <p>{@code reason} は {@link ApiBetaRevokeReason}（{@code TERMS_VIOLATION} /
 * {@code ACCOUNT_TRANSFER} / {@code OTHER}）に限定する。{@code WITHDRAWAL} はシステム専用値ゆえ
 * 本 enum に含めず、送信されても Jackson バインド失敗で 400 に倒す（03 §4）。</p>
 *
 * @param reason 取消事由（必須・{@code WITHDRAWAL} は指定不可）
 * @param note   監査用メモ（任意・500 文字以内）
 */
@Schema(name = "BetaPerkRevokeGrantRequest", description = "F20.3 シスアド ベータ特典 取消")
public record RevokeBetaGrantRequest(

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "TERMS_VIOLATION")
        @NotNull
        ApiBetaRevokeReason reason,

        @Schema(nullable = true, example = "規約第17条違反のため取消")
        @Size(max = 500)
        String note) {
}
