/**
 * F22.1 横スワイプ・スコープダッシュボード — タグ API composable
 *
 * 設計書: docs/features/F22.1_swipe_scope_dashboard/02_api_design.md §3.1〜§3.4
 *
 * API レスポンスは設計書 §3.1 / §3.3 のとおり **snake_case**（`scope_id` / `has_next` /
 * `unconfirmed_count` 等）で返る。フロント側の型（{@link ScopeTabPage} 等）は camelCase の
 * ため、本 composable が **API 境界で snake_case → camelCase へ正規化**する
 * （types/dashboard-scope.ts の方針コメント「API レスポンスは snake_case → camelCase に変換」を実装）。
 *
 * この変換が無いと `item.scope_id` が `item.scopeId`（undefined）として読まれ、
 * `getTeamDashboard(undefined)` が 400 になりチーム/組織パネルのウィジェットが描画されない。
 */
import type {
  ScopeTabType,
  ScopeTabPage,
  ScopeTabItem,
  ScopeTabOrderUpdate,
  ActionRequiredSummary,
  CirculationActionItem,
  SurveyActionItem,
  AttendanceActionItem,
} from '~/types/dashboard-scope'
import type {
  AdminActionRequiredSummary,
  AdminActionDomain,
  AdminActionDomainSummary,
  AdminActionItem,
} from '~/types/admin-action-required'
import type {
  AdminPaymentSummary,
  AdminBusinessAlert,
  AdminReportStats,
  AdminMemberStats,
  AdminReservationSummary,
  AdminBudgetSummary,
} from '~/types/admin-dashboard-widgets'

// ---- API レスポンス（snake_case）の生型 ----

interface RawScopeTabItem {
  scope_id: number
  public_id: string | null // UUID string（ダッシュボード API の pathVariable に使用）
  scope_type: ScopeTabType
  name: string
  avatar_url: string | null
  unread_count: number
  sort_order: number
}

interface RawScopeTabPage {
  items: RawScopeTabItem[]
  page: number
  page_size: number
  total_pages: number
  total_count: number
  has_next: boolean
  has_prev: boolean
}

interface RawActionRequiredSummary {
  circulation: {
    unconfirmed_count: number
    items: { id: string; title: string; circulated_at: string; deadline: string | null }[]
  }
  survey: {
    unanswered_count: number
    items: { id: number; title: string; deadline: string | null }[]
  }
  attendance: {
    unanswered_count: number
    items: { schedule_id: number; event_title: string; starts_at: string }[]
  }
  total_action_count: number
}

// ---- 管理者向け admin-action-required（snake_case）の生型（F10.1.1 P2b）----

interface RawAdminActionItem {
  id: string
  title: string
  requested_by: string
  requested_at: string
  detail_route: string
}

interface RawAdminActionDomainSummary {
  domain: AdminActionDomain
  pending_count: number
  degraded: boolean
  list_route: string
  items: RawAdminActionItem[]
}

interface RawAdminActionRequiredSummary {
  scope_type: 'TEAM' | 'ORGANIZATION'
  scope_id: number
  total_pending: number
  domains: RawAdminActionDomainSummary[]
}

// ---- snake_case → camelCase 変換 ----

function toScopeTabItem(r: RawScopeTabItem): ScopeTabItem {
  return {
    // scopeId は BIGINT のまま保持する（PUT /scope-tabs/order の scopeId は Long 型）。
    // ダッシュボード API の pathVariable には slug（カスタムスラッグ）を使用する。
    scopeId: String(r.scope_id),
    slug: r.public_id ?? null,
    scopeType: r.scope_type,
    name: r.name,
    avatarUrl: r.avatar_url,
    unreadCount: r.unread_count,
    sortOrder: r.sort_order,
  }
}

function toScopeTabPage(r: RawScopeTabPage): ScopeTabPage {
  return {
    items: (r.items ?? []).map(toScopeTabItem),
    page: r.page,
    pageSize: r.page_size,
    totalPages: r.total_pages,
    totalCount: r.total_count,
    hasNext: r.has_next,
    hasPrev: r.has_prev,
  }
}

function toActionRequiredSummary(r: RawActionRequiredSummary): ActionRequiredSummary {
  const circulationItems: CirculationActionItem[] = (r.circulation?.items ?? []).map((i) => ({
    id: i.id,
    title: i.title,
    circulatedAt: i.circulated_at,
    deadline: i.deadline,
  }))
  const surveyItems: SurveyActionItem[] = (r.survey?.items ?? []).map((i) => ({
    id: i.id,
    title: i.title,
    deadline: i.deadline,
  }))
  const attendanceItems: AttendanceActionItem[] = (r.attendance?.items ?? []).map((i) => ({
    scheduleId: i.schedule_id,
    eventTitle: i.event_title,
    startsAt: i.starts_at,
  }))
  return {
    circulation: {
      unconfirmedCount: r.circulation?.unconfirmed_count ?? 0,
      items: circulationItems,
    },
    survey: {
      unansweredCount: r.survey?.unanswered_count ?? 0,
      items: surveyItems,
    },
    attendance: {
      unansweredCount: r.attendance?.unanswered_count ?? 0,
      items: attendanceItems,
    },
    totalActionCount: r.total_action_count ?? 0,
  }
}

/**
 * 管理者向け admin-action-required（snake_case）→ camelCase 受信型へ変換する（F10.1.1 P2b）。
 *
 * メンバー向け {@link toActionRequiredSummary} とは別物。
 *
 * <p><b>degraded 正規化（検分🟠・二重防御）</b>: 集計失敗（degraded=true）のドメインは
 * BE 側で `total_pending` に加算されない前提だが、FE 側でも保証する。
 * degraded ドメインの `pendingCount` は 0 に正規化し（集計失敗を「件数」として表示・合算しない）、
 * `totalPending` も BE 値をそのまま信じず、正規化後の各ドメイン件数の合計で再計算する。
 * これにより万一 BE が degraded 分を含めて返しても、FE 側で件数 / total に混入しない。</p>
 */
function toAdminActionRequiredSummary(
  r: RawAdminActionRequiredSummary,
): AdminActionRequiredSummary {
  const domains: AdminActionDomainSummary[] = (r.domains ?? []).map((d) => {
    const items: AdminActionItem[] = (d.items ?? []).map((i) => ({
      id: i.id,
      title: i.title,
      requestedBy: i.requested_by,
      requestedAt: i.requested_at,
      detailRoute: i.detail_route,
    }))
    const degraded = d.degraded ?? false
    return {
      domain: d.domain,
      // degraded ドメインは件数を 0 に正規化（集計失敗を件数と混同しない・二重防御）。
      pendingCount: degraded ? 0 : (d.pending_count ?? 0),
      degraded,
      listRoute: d.list_route,
      items,
    }
  })
  // totalPending は BE 値を鵜呑みにせず、正規化後の各ドメイン件数（degraded=0）の合計で再計算する。
  const totalPending = domains.reduce((sum, d) => sum + d.pendingCount, 0)
  return {
    scopeType: r.scope_type,
    scopeId: r.scope_id,
    totalPending,
    domains,
  }
}

// ---- P3b Wave1 管理者ウィジェット用 API レスポンス（snake_case）の生型 ----

interface RawAdminPaymentSummary {
  unsettled_count: number
  overdue_count: number
}

interface RawAdminBusinessAlert {
  new_reservations: number
  unread_inquiries: number
}

interface RawAdminReportStats {
  pending_count: number
  reviewing_count: number
}

// ---- P3b Wave2 管理者ウィジェット用 API レスポンス（snake_case）の生型 ----

interface RawAdminMemberStats {
  total_count: number
  active_count: number
  new_this_month_count: number
}

interface RawAdminReservationSummary {
  pending_count: number
  today_count: number
}

interface RawAdminBudgetSummary {
  has_current_fiscal_year: boolean
  fiscal_year_name: string | null
  allocation: number
  actual: number
  remaining: number
  over_budget_category_count: number
}

// ---- P3b Wave1 snake_case → camelCase 変換 ----

function toAdminPaymentSummary(r: RawAdminPaymentSummary): AdminPaymentSummary {
  return {
    unsettledCount: r.unsettled_count ?? 0,
    overdueCount: r.overdue_count ?? 0,
  }
}

function toAdminBusinessAlert(r: RawAdminBusinessAlert): AdminBusinessAlert {
  return {
    newReservations: r.new_reservations ?? 0,
    unreadInquiries: r.unread_inquiries ?? 0,
  }
}

function toAdminReportStats(r: RawAdminReportStats): AdminReportStats {
  return {
    pendingCount: r.pending_count ?? 0,
    reviewingCount: r.reviewing_count ?? 0,
  }
}

// ---- P3b Wave2 snake_case → camelCase 変換 ----

function toAdminMemberStats(r: RawAdminMemberStats): AdminMemberStats {
  return {
    totalCount: r.total_count ?? 0,
    activeCount: r.active_count ?? 0,
    newThisMonthCount: r.new_this_month_count ?? 0,
  }
}

function toAdminReservationSummary(r: RawAdminReservationSummary): AdminReservationSummary {
  return {
    pendingCount: r.pending_count ?? 0,
    todayCount: r.today_count ?? 0,
  }
}

// ---- P3b Wave3 snake_case → camelCase 変換 ----

function toAdminBudgetSummary(r: RawAdminBudgetSummary): AdminBudgetSummary {
  return {
    hasCurrentFiscalYear: r.has_current_fiscal_year ?? false,
    fiscalYearName: r.fiscal_year_name ?? null,
    allocation: r.allocation ?? 0,
    actual: r.actual ?? 0,
    remaining: r.remaining ?? 0,
    overBudgetCategoryCount: r.over_budget_category_count ?? 0,
  }
}

/**
 * スコープタブ（タグ行）API の composable。
 *
 * - getScopeTabs: GET /api/v1/dashboard/scope-tabs（表示順適用済みの所属スコープ一覧）
 * - updateOrder: PUT /api/v1/dashboard/scope-tabs/order（タグ表示順の一括更新）
 * - getActionRequired: GET /api/v1/dashboard/{team|organization}/{id}/action-required
 */
export function useScopeTabApi() {
  const api = useApi()

  /**
   * 所属スコープの一覧を 6 件/ページで取得する。
   *
   * @param scopeType - TEAM / ORGANIZATION
   * @param page - 0 始まりのページ番号（デフォルト 0）
   * @param folderId - F15.3 フォルダ ID（指定時は当該フォルダに絞り込み）。
   *   `my_scope_folders.id` は数値（BIGINT）であり BE 実装（Long）と揃えるため number。
   *   URL クエリへは String(folderId) で付与する。
   */
  async function getScopeTabs(
    scopeType: ScopeTabType,
    page = 0,
    folderId?: number,
  ): Promise<ScopeTabPage> {
    const q = new URLSearchParams({ scopeType, page: String(page) })
    if (folderId !== undefined && folderId !== null) q.set('folderId', String(folderId))
    const res = await api<{ data: RawScopeTabPage }>(`/api/v1/dashboard/scope-tabs?${q}`)
    return toScopeTabPage(res.data)
  }

  /**
   * タグの表示順を一括更新する（UPSERT）。
   * リクエスト本体は設計書 §3.2 のとおり camelCase（`scopeId` / `sortOrder`）で送る。
   *
   * @param body - scopeType と orders 配列
   */
  async function updateOrder(body: ScopeTabOrderUpdate): Promise<void> {
    await api('/api/v1/dashboard/scope-tabs/order', { method: 'PUT', body })
  }

  /**
   * 統合「要対応」（回覧板/アンケート/出欠）の集計を取得する。
   *
   * @param scopeType - TEAM / ORGANIZATION
   * @param scopeId - チーム ID または組織 ID
   */
  async function getActionRequired(
    scopeType: ScopeTabType,
    scopeId: string,
  ): Promise<ActionRequiredSummary> {
    const base = scopeType === 'TEAM' ? `team/${scopeId}` : `organization/${scopeId}`
    const res = await api<{ data: RawActionRequiredSummary }>(
      `/api/v1/dashboard/${base}/action-required`,
    )
    return toActionRequiredSummary(res.data)
  }

  /**
   * 管理者向け横断「承認待ち」集約（予約承認待ち/シフトリクエスト/マッチング応募、または組織の未収請求）を
   * 取得する（F10.1.1 P2b・設計書 03）。
   *
   * メンバー向け {@link getActionRequired}（「私が回答/確認すべきこと」）とは**別物**。
   * こちらは ADMIN/DEPUTY が承認/処理すべきタスクを集約し、認可は BE の `checkAdminOrAbove` で担保される。
   *
   * パスの末尾には **slug** を使う（BE の `{teamSlug}` / `{orgSlug}`）。
   *
   * @param scopeType - TEAM / ORGANIZATION
   * @param slug - チーム / 組織の slug
   * @param previewSize - 各ドメインのプレビュー件数（0〜5）。0 で件数のみ（ハブのバッジ用途）。省略時は BE デフォルト（3）
   */
  async function getAdminActionRequired(
    scopeType: ScopeTabType,
    slug: string,
    previewSize?: number,
  ): Promise<AdminActionRequiredSummary> {
    const base = scopeType === 'TEAM' ? `team/${slug}` : `organization/${slug}`
    const query =
      previewSize !== undefined ? `?preview_size=${String(previewSize)}` : ''
    const res = await api<{ data: RawAdminActionRequiredSummary }>(
      `/api/v1/dashboard/${base}/admin-action-required${query}`,
    )
    return toAdminActionRequiredSummary(res.data)
  }


  /**
   * 組織の支払サマリ（ADMIN_ORG_PAYMENTS）を取得する（F10.1.1 P3b Wave1）。
   *
   * 対応 EP: GET /api/v1/dashboard/organization/{orgSlug}/admin-payment-summary
   *
   * @param orgSlug - 組織の slug
   */
  async function getAdminPaymentSummary(orgSlug: string): Promise<AdminPaymentSummary> {
    const res = await api<{ data: RawAdminPaymentSummary }>(
      `/api/v1/dashboard/organization/${orgSlug}/admin-payment-summary`,
    )
    return toAdminPaymentSummary(res.data)
  }

  /**
   * 業務アラートサマリ（ADMIN_TEAM_ALERT / ADMIN_ORG_ALERT）を取得する（F10.1.1 P3b Wave1）。
   *
   * 対応 EP:
   *   - GET /api/v1/dashboard/team/{teamSlug}/admin-business-alert
   *   - GET /api/v1/dashboard/organization/{orgSlug}/admin-business-alert
   *
   * 組織スコープは new_reservations=0 固定（§2.3⑤）。
   *
   * @param scopeType - TEAM / ORGANIZATION
   * @param slug - チーム / 組織の slug
   */
  async function getAdminBusinessAlert(
    scopeType: ScopeTabType,
    slug: string,
  ): Promise<AdminBusinessAlert> {
    const base = scopeType === 'TEAM' ? `team/${slug}` : `organization/${slug}`
    const res = await api<{ data: RawAdminBusinessAlert }>(
      `/api/v1/dashboard/${base}/admin-business-alert`,
    )
    return toAdminBusinessAlert(res.data)
  }

  /**
   * 通報統計（ADMIN_TEAM_REPORTS / ADMIN_ORG_REPORTS）を取得する（F10.1.1 P3b Wave1）。
   *
   * 対応 EP:
   *   - GET /api/v1/dashboard/team/{teamSlug}/admin-report-stats
   *   - GET /api/v1/dashboard/organization/{orgSlug}/admin-report-stats
   *
   * @param scopeType - TEAM / ORGANIZATION
   * @param slug - チーム / 組織の slug
   */
  async function getAdminReportStats(
    scopeType: ScopeTabType,
    slug: string,
  ): Promise<AdminReportStats> {
    const base = scopeType === 'TEAM' ? `team/${slug}` : `organization/${slug}`
    const res = await api<{ data: RawAdminReportStats }>(
      `/api/v1/dashboard/${base}/admin-report-stats`,
    )
    return toAdminReportStats(res.data)
  }

  /**
   * メンバー統計（ADMIN_TEAM_MEMBERS / ADMIN_ORG_MEMBERS）を取得する（F10.1.1 P3b Wave2）。
   *
   * 対応 EP:
   *   - GET /api/v1/dashboard/team/{teamSlug}/admin-member-stats
   *   - GET /api/v1/dashboard/organization/{orgSlug}/admin-member-stats
   *
   * @param scopeType - TEAM / ORGANIZATION
   * @param slug - チーム / 組織の slug
   */
  async function getAdminMemberStats(
    scopeType: ScopeTabType,
    slug: string,
  ): Promise<AdminMemberStats> {
    const base = scopeType === 'TEAM' ? `team/${slug}` : `organization/${slug}`
    const res = await api<{ data: RawAdminMemberStats }>(
      `/api/v1/dashboard/${base}/admin-member-stats`,
    )
    return toAdminMemberStats(res.data)
  }

  /**
   * 予約サマリ（ADMIN_TEAM_RESERVATIONS）を取得する（F10.1.1 P3b Wave2・team 専用）。
   *
   * 対応 EP: GET /api/v1/dashboard/team/{teamSlug}/admin-reservation-summary
   *
   * @param teamSlug - チームの slug
   */
  async function getAdminReservationSummary(teamSlug: string): Promise<AdminReservationSummary> {
    const res = await api<{ data: RawAdminReservationSummary }>(
      `/api/v1/dashboard/team/${teamSlug}/admin-reservation-summary`,
    )
    return toAdminReservationSummary(res.data)
  }

  /**
   * 予算サマリ（ADMIN_TEAM_BUDGET / ADMIN_ORG_BUDGET）を取得する（F10.1.1 P3b Wave3・team/org 両対応）。
   *
   * 対応 EP:
   *   - GET /api/v1/dashboard/team/{teamSlug}/admin-budget-summary
   *   - GET /api/v1/dashboard/organization/{orgSlug}/admin-budget-summary
   *
   * @param scopeType - TEAM / ORGANIZATION
   * @param slug - チーム / 組織の slug
   */
  async function getAdminBudgetSummary(
    scopeType: ScopeTabType,
    slug: string,
  ): Promise<AdminBudgetSummary> {
    const base = scopeType === 'TEAM' ? `team/${slug}` : `organization/${slug}`
    const res = await api<{ data: RawAdminBudgetSummary }>(
      `/api/v1/dashboard/${base}/admin-budget-summary`,
    )
    return toAdminBudgetSummary(res.data)
  }

  return {
    getScopeTabs,
    updateOrder,
    getActionRequired,
    getAdminActionRequired,
    getAdminPaymentSummary,
    getAdminBusinessAlert,
    getAdminReportStats,
    getAdminMemberStats,
    getAdminReservationSummary,
    getAdminBudgetSummary,
  }
}


