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

/**
 * メンバー統計（ADMIN_TEAM_MEMBERS / ADMIN_ORG_MEMBERS・§2.2④ §2.3④）— P3b Wave2。
 *
 * 対応 EP:
 *   - GET /api/v1/dashboard/team/{teamSlug}/admin-member-stats
 *   - GET /api/v1/dashboard/organization/{orgSlug}/admin-member-stats
 *
 * memberships（在籍）由来。総数は管理者（ADMIN/DEPUTY）も含めた全在籍者。
 */
export interface AdminMemberStats {
  /** 会員総数（total_count・管理者含む全在籍者）。 */
  totalCount: number
  /** アクティブ会員数（active_count・users.status='ACTIVE'）。 */
  activeCount: number
  /** 今月新規会員数（new_this_month_count・joined_at が当月 JST）。 */
  newThisMonthCount: number
}

/**
 * 予約サマリ（ADMIN_TEAM_RESERVATIONS・§2.2①）— P3b Wave2。team 専用。
 *
 * 対応 EP: GET /api/v1/dashboard/team/{teamSlug}/admin-reservation-summary
 */
export interface AdminReservationSummary {
  /** 承認待ち件数（pending_count・status=PENDING）。 */
  pendingCount: number
  /** 本日の予約数（today_count・本日 JST の CONFIRMED/PENDING）。 */
  todayCount: number
}

/**
 * 予算サマリ（ADMIN_TEAM_BUDGET / ADMIN_ORG_BUDGET）— P3b Wave3。team/org 両対応。
 *
 * 対応 EP:
 *   - GET /api/v1/dashboard/team/{teamSlug}/admin-budget-summary
 *   - GET /api/v1/dashboard/organization/{orgSlug}/admin-budget-summary
 *
 * 現年度（today を期間に含む年度）の集計。現年度が無い場合は hasCurrentFiscalYear=false・各数値0・名称null。
 * 配分=配分合計 / 実績=承認済みEXPENSE合計 / 残=配分−実績 / 超過カテゴリ数=カテゴリ毎の残が負の数。
 */
export interface AdminBudgetSummary {
  /** 現年度が存在するか（has_current_fiscal_year）。false なら数値は0・名称はnull。 */
  hasCurrentFiscalYear: boolean
  /** 現年度名（fiscal_year_name・未設定時 null）。 */
  fiscalYearName: string | null
  /** 配分合計（allocation）。 */
  allocation: number
  /** 実績合計（actual・承認済み EXPENSE）。 */
  actual: number
  /** 残（remaining・配分−実績・負になり得る）。 */
  remaining: number
  /** 超過カテゴリ数（over_budget_category_count）。 */
  overBudgetCategoryCount: number
}
