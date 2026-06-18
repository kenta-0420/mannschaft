package com.mannschaft.app.dashboard.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;

/**
 * F10.1.1 / P3b: チーム/組織パネル管理者レンズ ⑥
 * {@code ADMIN_TEAM_REPORTS} / {@code ADMIN_ORG_REPORTS} のサマリ DTO（通報・スコープ単位）。
 *
 * <p>既存の {@code ReportStatsResponse}（{@code ReportActionService.getStats()}）は全体集計で
 * スコープを無視するため、本サマリは {@code scope_type + scope_id} で絞り込んだ「未対応／確認中」件数を返す
 * （設計書 02 §2.2 ⑥ / §2.3 ⑥）。集約には既存の
 * {@code ContentReportRepository.countByScopeTypeAndScopeIdAndStatus} を使う（WHERE に scope 列必須・IDOR 防止）。</p>
 *
 * <p>JSON は snake_case（プロジェクト REST 規約）。</p>
 *
 * <p>設計書: docs/features/F10.1.1_team_org_admin_console/02_admin_lens_widgets.md §2.2 ⑥ / §2.3 ⑥</p>
 */
@Builder
public record AdminReportStatsResponse(

        /** 未対応件数（ReportStatus.PENDING）。 */
        @JsonProperty("pending_count") long pendingCount,

        /** 確認中件数（ReportStatus.REVIEWING）。 */
        @JsonProperty("reviewing_count") long reviewingCount
) {
}
