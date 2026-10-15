package com.mannschaft.app.organization.dto;

import com.mannschaft.app.organization.TeamApplicationGroupMode;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * チーム加盟の申請受付・グループ設定の更新要求（F01.2.1 §10.2）。
 *
 * <p>PUT は全項目の置き換え（部分更新にしない）。{@code applicationGuidance} だけは null 可で、
 * 空文字は null として保存する。「キーを送らない」と「null を送る」は区別しない（§10 共通事項）。</p>
 *
 * <p>入力検証は認可の後に行う（§10 共通事項の順序: 404 → 403 → 400）。そのため Controller では
 * {@code @Valid} を付けず、認可を通った後に Service 側で検証する。</p>
 */
public record UpdateTeamAffiliationSettingsRequest(
        @NotNull Boolean teamApplicationEnabled,
        @NotNull Boolean teamGroupsEnabled,
        @NotNull TeamApplicationGroupMode applicationGroupMode,
        @Size(max = 500) String applicationGuidance
) {

    /** 空文字・空白だけの案内文を null に正規化した値。 */
    public String normalizedGuidance() {
        return applicationGuidance == null || applicationGuidance.isBlank() ? null : applicationGuidance;
    }
}
