/**
 * F10.1.1 / P3b Wave1 — 管理者レンズ L1 ウィジェット用 FE 受信型（camelCase）。
 *
 * 設計書: docs/features/F10.1.1_team_org_admin_console/02_admin_lens_widgets.md §2.2③ §2.3③ §2.2⑤ §2.3⑤ §2.2⑥ §2.3⑥ §2.2⑦
 *
 * API レスポンスは snake_case。`useScopeTabApi` が API 境界で snake_case → camelCase へ変換し、
 * 本型で消費する（any 禁止・生成型連携）。
 */

/**
 * 組織の支払サマリ（ADMIN_ORG_PAYMENTS・§2.3③）。
 *
 * 対応 EP: GET /api/v1/dashboard/organization/{orgSlug}/admin-payment-summary
 */
export interface AdminPaymentSummary {
  /** 未収件数（unsettled_count）。 */
  unsettledCount: number
  /** 期限超過件数（overdue_count）。 */
  overdueCount: number
}

/**
 * 業務アラートサマリ（ADMIN_TEAM_ALERT / ADMIN_ORG_ALERT・§2.2⑤ §2.3⑤）。
 *
 * 対応 EP:
 *   - GET /api/v1/dashboard/team/{teamSlug}/admin-business-alert
 *   - GET /api/v1/dashboard/organization/{orgSlug}/admin-business-alert
 *
 * 組織スコープは new_reservations=0 固定（§2.3⑤：組織には予約ウィジェットを置かない）。
 */
export interface AdminBusinessAlert {
  /** 新規予約件数（new_reservations）。組織スコープは 0 固定。 */
  newReservations: number
  /** 未読問い合わせ件数（unread_inquiries）。 */
  unreadInquiries: number
}

/**
 * 通報統計（ADMIN_TEAM_REPORTS / ADMIN_ORG_REPORTS・§2.2⑥ §2.3⑥）。
 *
 * 対応 EP:
 *   - GET /api/v1/dashboard/team/{teamSlug}/admin-report-stats
 *   - GET /api/v1/dashboard/organization/{orgSlug}/admin-report-stats
 */
export interface AdminReportStats {
  /** 未対応件数（pending_count）。 */
  pendingCount: number
  /** 確認中件数（reviewing_count）。 */
  reviewingCount: number
}
