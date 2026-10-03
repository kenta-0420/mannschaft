package com.mannschaft.app.dashboard.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;

/**
 * F10.1.1 / P3b: チーム/組織パネル管理者レンズ ⑤
 * {@code ADMIN_TEAM_ALERT} / {@code ADMIN_ORG_ALERT} のサマリ DTO（業務アラート・スコープ単位）。
 *
 * <p>個人ダッシュボードの全所属横断ウィジェット（{@code WidgetAdminBusinessAlert}・F10.7）に対し、
 * 本サマリは「いま見ているチーム/組織 1 件に集中」したスコープ単位値を返す（設計書 02 §3）。</p>
 *
 * <p><b>承認待ち（③ ADMIN_*_APPROVALS）との二重計上回避</b>: 本サマリは「新規予約（本日入った予約の
 * 通知的件数）」と「未読問い合わせ」のみを持つ。承認待ち件数（pending）は P1 集約 API
 * （{@link AdminActionRequiredResponse} の {@code total_pending}）に一本化し、ここには含めない
 * （設計書 02 §3）。</p>
 *
 * <p>組織スコープには予約 API が無いため（{@code ReservationEntity} に organization_id 無し）、
 * 組織の業務アラートは {@code new_reservations=0} 固定で {@code unread_inquiries} のみが意味を持つ。</p>
 *
 * <p>JSON は snake_case（プロジェクト REST 規約）。</p>
 *
 * <p>設計書: docs/features/F10.1.1_team_org_admin_console/02_admin_lens_widgets.md §2.2 ⑤ / §2.3 ⑤ / §3</p>
 */
@Builder
public record AdminBusinessAlertScopeResponse(

        /** 新規予約件数（本日 JST 0:00 以降に入った CONFIRMED 予約）。組織スコープは常に 0。 */
        @JsonProperty("new_reservations") long newReservations,

        /** 未読問い合わせ件数（当該スコープの問い合わせチャンネルの閲覧者未読合計）。 */
        @JsonProperty("unread_inquiries") long unreadInquiries
) {
}
