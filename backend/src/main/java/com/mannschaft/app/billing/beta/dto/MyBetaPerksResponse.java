package com.mannschaft.app.billing.beta.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * F20.3 ベータ特典: {@code GET /me/beta-perks} のレスポンス（設計書 02 §1.1）。
 *
 * <p>本人固定（scopeId を受けない・IDOR 無効化・AC-A5）。{@code eligibility} は現行フェーズの criteria が
 * 未定義/{@code enabled=false} のとき {@code null}（{@code CRITERIA_NOT_FOUND} を catch・AC-N4）。</p>
 */
@Getter
@Builder
@Schema(name = "BetaPerkMyPerksResponse", description = "F20.3 自分のベータ特典")
public class MyBetaPerksResponse {

    @Schema(description = "自分に付与された特典一覧（空配列可）")
    private final List<BetaGrantItem> grants;

    @Schema(description = "現行フェーズの充足状況（criteria 未定義時は null）", nullable = true)
    private final EligibilityStatus eligibility;
}
