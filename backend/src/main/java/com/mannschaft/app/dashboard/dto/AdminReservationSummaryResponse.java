package com.mannschaft.app.dashboard.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;

/**
 * F10.1.1 / P3b Wave2: チームパネル管理者レンズ「予約サマリ」の DTO
 * （{@code ADMIN_TEAM_RESERVATIONS}・設計書 02 §2.2①）。
 *
 * <p>当該チームの「承認待ち件数（status=PENDING）」と「本日の予約数（本日 JST に予約された
 * 有効予約＝CONFIRMED/PENDING）」を返す。組織スコープには予約 API が無いため本サマリは team 専用。</p>
 *
 * <p>JSON は snake_case（プロジェクト REST 規約・FE は camelCase へ変換）。</p>
 *
 * <p>設計書: docs/features/F10.1.1_team_org_admin_console/02_admin_lens_widgets.md §2.2①</p>
 */
@Builder
public record AdminReservationSummaryResponse(

        /** 承認待ち件数（status=PENDING）。P1 承認待ち集約の予約ドメインと同じ断面。 */
        @JsonProperty("pending_count") long pendingCount,

        /** 本日の予約数（本日 JST に予約された CONFIRMED/PENDING の有効予約・キャンセル除く）。 */
        @JsonProperty("today_count") long todayCount
) {
}
