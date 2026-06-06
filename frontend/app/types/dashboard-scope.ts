/**
 * F22.1 横スワイプ・スコープダッシュボード — 型定義
 *
 * 設計書: docs/features/F22.1_swipe_scope_dashboard/03_security_ux.md §2.1
 */

// -----------------------------------------------------------------------
// タグ（スコープタブ）関連
// -----------------------------------------------------------------------

/** スコープ種別 */
export type ScopeTabType = 'TEAM' | 'ORGANIZATION'

/** タグ行の 1 スコープエントリ（API レスポンスは snake_case → camelCase に変換） */
export interface ScopeTabItem {
  scopeId: string
  scopeType: ScopeTabType
  name: string
  avatarUrl: string | null
  unreadCount: number
  sortOrder: number
}

/** GET /dashboard/scope-tabs のページングレスポンス */
export interface ScopeTabPage {
  items: ScopeTabItem[]
  page: number
  pageSize: number
  totalPages: number
  totalCount: number
  hasNext: boolean
  hasPrev: boolean
}

/** PUT /dashboard/scope-tabs/order のリクエストボディ */
export interface ScopeTabOrderUpdate {
  scopeType: ScopeTabType
  orders: { scopeId: string; sortOrder: number }[]
}

// -----------------------------------------------------------------------
// 統合「要対応」ウィジェット関連
// -----------------------------------------------------------------------

/** 回覧板の未確認アイテム */
export interface CirculationActionItem {
  id: string
  title: string
  circulatedAt: string
  deadline: string | null
}

/** アンケートの未回答アイテム */
export interface SurveyActionItem {
  id: number
  title: string
  deadline: string | null
}

/** 出席確認の未回答アイテム */
export interface AttendanceActionItem {
  scheduleId: number
  eventTitle: string
  startsAt: string
}

/** 統合「要対応」集計（API レスポンスと同形） */
export interface ActionRequiredSummary {
  circulation: {
    unconfirmedCount: number
    items: CirculationActionItem[]
  }
  survey: {
    unansweredCount: number
    items: SurveyActionItem[]
  }
  attendance: {
    unansweredCount: number
    items: AttendanceActionItem[]
  }
  totalActionCount: number
}

// -----------------------------------------------------------------------
// dashboard.ts の WidgetVisibilityRowDto に対応する型（F02.2.1 互換）
// -----------------------------------------------------------------------

/** ウィジェット可視性行（TeamDashboardResponse / OrgDashboardResponse に含まれる） */
export interface WidgetVisibilityRow {
  widgetKey: string
  minRole: 'PUBLIC' | 'SUPPORTER' | 'MEMBER'
  isVisible: boolean
}

/** ウィジェット設定行（widget_settings 配列の要素） */
export interface WidgetSetting {
  widgetKey: string
  name: string
  isVisible: boolean
  sortOrder: number
  isModuleEnabled: boolean
  disabledReason: string | null
}

// -----------------------------------------------------------------------
// ブログ・チャット・カレンダーのサマリ型（第二波で実体が入るまでオプショナル）
// -----------------------------------------------------------------------

/** ブログ直近記事エントリ */
export interface LatestBlogPostEntry {
  id: number
  title: string
  author: string
  publishedAt: string
}

/** チャットチャンネルサマリエントリ */
export interface ChatChannelSummaryEntry {
  id: number
  name: string
  unreadCount: number
  lastMessagePreview: string | null
}

/** チャットサマリ */
export interface ChatSummary {
  totalUnread: number
  channels: ChatChannelSummaryEntry[]
}

/** カレンダーサマリ */
export interface CalendarSummary {
  eventsToday: number
  eventsThisWeek: number
  nextEvent: string | null
  daysWithEvents: number[]
}

// -----------------------------------------------------------------------
// TeamDashboardResponse / OrgDashboardResponse のミラー型
// 既存 Java DTO のフィールドを正確にミラー + F22.1 第二波追加フィールドをオプショナルで含める
// -----------------------------------------------------------------------

/**
 * GET /api/v1/dashboard/team/{teamId} のレスポンス型。
 *
 * 現行実装済みフィールドはそのまま定義し、
 * 第二波（F22.1 Wave 2）で追加予定のウィジェットサマリはオプショナルで宣言する。
 */
export interface TeamDashboardResponse {
  // --- 既存実装済みフィールド ---
  teamNotices: Record<string, unknown>[] | null
  teamUpcomingEvents: Record<string, unknown>[] | null
  teamTodo: Record<string, unknown> | null
  teamProjectProgress: Record<string, unknown>[] | null
  teamActivity: Record<string, unknown> | null
  teamLatestPosts: Record<string, unknown>[] | null
  teamUnreadThreads: Record<string, unknown> | null
  teamMemberAttendance: Record<string, unknown> | null
  teamBilling: Record<string, unknown> | null
  teamPageViews: Record<string, unknown> | null
  widgetSettings: WidgetSetting[] | null
  platformAnnouncements: Record<string, unknown>[] | null
  // F02.2.1 追加フィールド
  viewerRole: 'SYSTEM_ADMIN' | 'ADMIN' | 'DEPUTY_ADMIN' | 'MEMBER' | 'SUPPORTER' | 'PUBLIC' | null
  widgetVisibility: WidgetVisibilityRow[] | null

  // --- 第二波（F22.1 Wave 2）で追加予定のオプショナルフィールド ---
  // 02_api_design.md §3.3 サマリ追加フィールド参照
  teamLatestBlogPosts?: LatestBlogPostEntry[]
  teamChatSummary?: ChatSummary
  teamCalendarSummary?: CalendarSummary
  teamActionRequired?: ActionRequiredSummary
}

/**
 * GET /api/v1/dashboard/organization/{orgId} のレスポンス型。
 *
 * 現行実装済みフィールドはそのまま定義し、
 * 第二波（F22.1 Wave 2）で追加予定のウィジェットサマリはオプショナルで宣言する。
 */
export interface OrgDashboardResponse {
  // --- 既存実装済みフィールド ---
  orgTeamList: Record<string, unknown>[] | null
  orgNotices: Record<string, unknown>[] | null
  orgTodo: Record<string, unknown> | null
  orgProjectProgress: Record<string, unknown>[] | null
  orgStats: Record<string, unknown> | null
  orgBilling: Record<string, unknown> | null
  widgetSettings: WidgetSetting[] | null
  platformAnnouncements: Record<string, unknown>[] | null
  // F02.2.1 追加フィールド
  viewerRole: 'SYSTEM_ADMIN' | 'ADMIN' | 'DEPUTY_ADMIN' | 'MEMBER' | 'SUPPORTER' | 'PUBLIC' | null
  widgetVisibility: WidgetVisibilityRow[] | null

  // --- 第二波（F22.1 Wave 2）で追加予定のオプショナルフィールド ---
  // 02_api_design.md §3.3 サマリ追加フィールド参照
  orgUpcomingEvents?: Record<string, unknown>[]
  orgLatestPosts?: Record<string, unknown>[]
  orgUnreadThreads?: Record<string, unknown>
  orgLatestBlogPosts?: LatestBlogPostEntry[]
  orgChatSummary?: ChatSummary
  orgCalendarSummary?: CalendarSummary
  orgActionRequired?: ActionRequiredSummary
}
