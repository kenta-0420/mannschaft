package com.mannschaft.app.dashboard.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;

/**
 * F10.1.1 / P3b Wave2: チーム/組織パネル管理者レンズ「メンバー統計」のサマリ DTO
 * （{@code ADMIN_TEAM_MEMBERS} / {@code ADMIN_ORG_MEMBERS}・設計書 02 §2.2④ / §2.3④）。
 *
 * <p>memberships（{@code left_at IS NULL}）を在籍の真実の源とし、当該スコープの
 * 「総数 / アクティブ / 今月新規」を 3 区分で返す。総数は管理者（ADMIN/DEPUTY）も含めた
 * 全在籍者（管理者・チーム作成者も memberships に MEMBER 行を持つ）。</p>
 *
 * <p>JSON は snake_case（プロジェクト REST 規約・FE は camelCase へ変換）。</p>
 *
 * <p>設計書: docs/features/F10.1.1_team_org_admin_console/02_admin_lens_widgets.md §2.2④ / §2.3④</p>
 */
@Builder
public record AdminMemberStatsResponse(

        /** 会員総数（memberships の active な DISTINCT user_id 件数・管理者含む全員）。 */
        @JsonProperty("total_count") long totalCount,

        /** アクティブ会員数（在籍者のうち users.status='ACTIVE' かつ未削除のユーザー数）。 */
        @JsonProperty("active_count") long activeCount,

        /** 今月新規会員数（在籍者のうち joined_at が当月（JST）に入った DISTINCT user_id 件数）。 */
        @JsonProperty("new_this_month_count") long newThisMonthCount
) {
}
