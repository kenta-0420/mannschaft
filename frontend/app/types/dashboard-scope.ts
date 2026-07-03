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
  /** 内部 BIGINT（表示順管理・PUT /scope-tabs/order の scopeId に使用） */
  scopeId: string
  /** カスタムスラッグ（ダッシュボード API の pathVariable /team/{slug} 等に使用）。BE が返さない場合は null */
  slug: string | null
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
// チーム/組織パネル用のウィジェット表示アイテム型
//
// BE は各ウィジェットを Map<String,Object> でシリアライズしており（生成型
// types/generated では中身が空型になる）、キーは既存ダッシュボードレスポンスと
// 同じ snake_case のまま返る（Jackson の命名変換は生 Map には効かない）。
// FE はそのキーを正確にミラーした表示用型として本ファイルへ集約する。
// 権威源: backend ScopeWidgetSummaryService / DashboardService の toXxxMap。
// -----------------------------------------------------------------------

/** ① 今後の予定エントリ（teamUpcomingEvents / orgUpcomingEvents の要素）。 */
export interface ScopeUpcomingEventItem {
  id: number
  title: string
  start_at: string
  end_at: string | null
  location: string | null
  all_day: boolean
}

/** ④ ブログ直近記事エントリ（teamLatestBlogPosts / orgLatestBlogPosts の要素）。 */
export interface ScopeBlogPostItem {
  id: number
  title: string
  author: string | null
  published_at: string | null
}

/** ⑤ チャットチャンネルサマリエントリ（chatSummary.channels の要素）。 */
export interface ScopeChatChannelItem {
  id: number
  name: string
  unread_count: number
  last_message_preview: string | null
}

/** ⑤ チャットサマリ（teamChatSummary / orgChatSummary）。 */
export interface ScopeChatSummary {
  total_unread: number
  channels: ScopeChatChannelItem[]
}

/** ⑥ カレンダーサマリ（teamCalendarSummary / orgCalendarSummary）。 */
export interface ScopeCalendarSummary {
  events_today: number
  events_this_week: number
  next_event: string | null
  days_with_events: number[]
}

/** ⑦ TODO アイテム（teamTodo.items / orgTodo.items の要素）。 */
export interface ScopeTodoItem {
  id: number
  title: string
  status: string
  priority: string
  due_date: string | null
  parent_id: number | null
  depth: number
}

/** ⑦ TODO サマリ（teamTodo / orgTodo）。 */
export interface ScopeTodoSummary {
  items: ScopeTodoItem[]
  overdue_count: number
  total_incomplete: number
}

/** ③ 未読スレッド集計（teamUnreadThreads / orgUnreadThreads）。リスト無し（第二陣で拡張予定）。 */
export interface ScopeUnreadThreads {
  bulletin_count: number
  chat_count: number
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
  teamUpcomingEvents: ScopeUpcomingEventItem[] | null
  teamTodo: ScopeTodoSummary | null
  teamProjectProgress: Record<string, unknown>[] | null
  teamActivity: Record<string, unknown> | null
  teamLatestPosts: Record<string, unknown>[] | null
  teamUnreadThreads: ScopeUnreadThreads | null
  teamMemberAttendance: Record<string, unknown> | null
  teamBilling: Record<string, unknown> | null
  teamPageViews: Record<string, unknown> | null
  widgetSettings: WidgetSetting[] | null
  platformAnnouncements: Record<string, unknown>[] | null
  // F02.2.1 追加フィールド
  viewerRole: 'SYSTEM_ADMIN' | 'ADMIN' | 'DEPUTY_ADMIN' | 'MEMBER' | 'SUPPORTER' | 'PUBLIC' | null
  widgetVisibility: WidgetVisibilityRow[] | null

  // --- 第二波（F22.1 Wave 2）で実装済みのサマリフィールド ---
  // 02_api_design.md §3.3 サマリ追加フィールド参照
  teamLatestBlogPosts?: ScopeBlogPostItem[] | null
  teamChatSummary?: ScopeChatSummary | null
  teamCalendarSummary?: ScopeCalendarSummary | null
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
  orgTodo: ScopeTodoSummary | null
  orgProjectProgress: Record<string, unknown>[] | null
  orgStats: Record<string, unknown> | null
  orgBilling: Record<string, unknown> | null
  widgetSettings: WidgetSetting[] | null
  platformAnnouncements: Record<string, unknown>[] | null
  // F02.2.1 追加フィールド
  viewerRole: 'SYSTEM_ADMIN' | 'ADMIN' | 'DEPUTY_ADMIN' | 'MEMBER' | 'SUPPORTER' | 'PUBLIC' | null
  widgetVisibility: WidgetVisibilityRow[] | null

  // --- 第二波（F22.1 Wave 2）で実装済みのサマリフィールド ---
  // 02_api_design.md §3.3 サマリ追加フィールド参照
  orgUpcomingEvents?: ScopeUpcomingEventItem[] | null
  orgLatestPosts?: Record<string, unknown>[] | null
  orgUnreadThreads?: ScopeUnreadThreads | null
  orgLatestBlogPosts?: ScopeBlogPostItem[] | null
  orgChatSummary?: ScopeChatSummary | null
  orgCalendarSummary?: ScopeCalendarSummary | null
  orgActionRequired?: ActionRequiredSummary
}
