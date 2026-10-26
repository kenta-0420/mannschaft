package com.mannschaft.app.common.storage.quota.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * F13 スコープ別ストレージ使用量（{@code GET /api/v1/me/storage/usage} のレスポンス要素）。
 *
 * <p>本人が所属する各スコープ（個人・所属チーム・所属組織）1 件分の使用量・上限を表す。
 * クライアントから {@code scopeId} を受け取らずサーバーが本人の所属を列挙して返すため、
 * 恣意的な ID 注入による他スコープの使用量参照（漏洩）を構造的に排除している。</p>
 *
 * @param scopeType     スコープ種別（{@code PERSONAL} / {@code TEAM} / {@code ORGANIZATION}）
 * @param scopeId       スコープ ID（PERSONAL=本人の users.id / TEAM=teams.id / ORGANIZATION=organizations.id）
 * @param scopeName     スコープ表示名（チーム名・組織名。PERSONAL は「個人」）
 * @param slug          URL slug（TEAM / ORGANIZATION のみ。PERSONAL は {@code null}）
 * @param usedBytes     使用済みバイト数（subscription 未作成スコープは 0）
 * @param fileCount     ファイル数（subscription 未作成スコープは 0）
 * @param includedBytes プラン無料枠バイト数（subscription があればその plan、無ければ scope_level のデフォルトプラン）
 * @param maxBytes      プラン上限バイト数。無制限プランは {@code null}
 * @param usagePercent  使用率（%）。{@code includedBytes > 0} のとき {@code usedBytes / includedBytes * 100}、
 *                      {@code includedBytes == 0} のとき 0。超過プランでは 100 を超え得る
 */
@Schema(description = "スコープ別ストレージ使用量")
public record StorageScopeUsage(
        @Schema(description = "スコープ種別", example = "TEAM",
                allowableValues = {"PERSONAL", "TEAM", "ORGANIZATION"})
        String scopeType,

        @Schema(description = "スコープ ID", example = "10")
        Long scopeId,

        @Schema(description = "スコープ表示名", example = "サッカー部")
        String scopeName,

        @Schema(description = "URL slug（PERSONAL は null）", example = "soccer-club", nullable = true)
        String slug,

        @Schema(description = "使用済みバイト数", example = "1048576")
        Long usedBytes,

        @Schema(description = "ファイル数", example = "12")
        Integer fileCount,

        @Schema(description = "プラン無料枠バイト数", example = "10737418240")
        Long includedBytes,

        @Schema(description = "プラン上限バイト数（無制限は null）", example = "53687091200", nullable = true)
        Long maxBytes,

        @Schema(description = "使用率（%）。includedBytes=0 のときは 0", example = "9.77")
        double usagePercent
) {
}
