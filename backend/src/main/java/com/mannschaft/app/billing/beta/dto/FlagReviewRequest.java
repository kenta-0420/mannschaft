package com.mannschaft.app.billing.beta.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/**
 * F20.3 ベータ特典: シスアド 手動審査フラグ設定リクエスト（設計書 02 §4.4）。
 *
 * <p>{@code review_reason='MANUAL'} 固定で立てる（事由は API から選ばせない）。{@code note} は監査用の任意メモ。
 * 取消済み grant へのフラグは {@code BetaGrantService.flagReview} が
 * {@code GRANT_ALREADY_REVOKED}(409) で拒否する。</p>
 *
 * @param note 監査用メモ（任意・500 文字以内）
 */
@Schema(name = "BetaPerkFlagReviewRequest", description = "F20.3 シスアド ベータ特典 審査フラグ設定")
public record FlagReviewRequest(

        @Schema(nullable = true, example = "オーナー変更の疑いのため手動フラグ")
        @Size(max = 500)
        String note) {
}
