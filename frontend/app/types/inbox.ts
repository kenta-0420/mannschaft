/**
 * F04.11 統合通知インボックス — フロントエンド型定義。
 *
 * BE: com.mannschaft.app.inbox パッケージの enum / DTO に対応。
 * 設計書: docs/features/F04.11_notification_inbox/01_data_model.md §3
 *        02_api_design.md §3
 */

/** フィルタ用状態語彙（GETクエリの state パラメータ）。 */
export type InboxStateFilter = 'INBOX' | 'SNOOZED' | 'ARCHIVED' | 'ALL'

/** アイテム個別の状態（InboxItem.state）。 */
export type InboxState = 'UNREAD' | 'READ' | 'SNOOZED' | 'ARCHIVED'

/** 自動緊急度。 */
export type InboxPriority = 'URGENT' | 'HIGH' | 'NORMAL' | 'LOW'

/**
 * 通知ソース種別。
 * MVP実装（NOTIFICATION / TODO_DUE）＋将来追加分（ANNOUNCEMENT / MENTION / CONFIRMABLE）の
 * i18n/アイコン器を先行定義する（出陣③のBE追加で実データが流れてくる）。
 */
export type InboxSourceType =
  | 'NOTIFICATION'
  | 'ANNOUNCEMENT'
  | 'MENTION'
  | 'CONFIRMABLE'
  | 'TODO_DUE'

/** ラベル DTO（LabelDto）。 */
export interface InboxLabel {
  id: string
  name: string
  color: string | null
  icon: string | null
  sortOrder: number
}

/** スコープ情報（InboxItemDto.ScopeDto）。 */
export interface InboxScope {
  type: string
  id: number | null
  name: string | null
}

/**
 * インボックスアイテム（InboxItemDto）。
 * id は "{sourceType}:{sourceId}" の複合論理キー。
 */
export interface InboxItem {
  id: string
  sourceType: InboxSourceType
  sourceId: number
  title: string
  excerpt: string | null
  priority: InboxPriority
  scope: InboxScope | null
  actionUrl: string | null
  occurredAt: string
  state: InboxState
  snoozedUntil: string | null
  labels: InboxLabel[]
}

/** 一覧レスポンス（InboxPageResponse を ApiResponse でラップしたもの）。 */
export interface InboxListResponse {
  data: {
    items: InboxItem[]
    page: number
    size: number
    totalEstimated: number
    hasMore: boolean
  }
}

/** サマリレスポンス（InboxSummaryResponse を ApiResponse でラップしたもの）。 */
export interface InboxSummary {
  data: {
    byState: Record<string, number>
    byPriority: Record<string, number>
    bySourceType: Record<string, number>
  }
}

/** triage 操作（snooze/unsnooze/archive/unarchive）のレスポンス。 */
export interface InboxTriageResponse {
  data: InboxItem
}

/** GET /api/v1/inbox クエリパラメータ。 */
export interface InboxListParams {
  state?: InboxStateFilter
  priority?: InboxPriority[]
  sourceType?: InboxSourceType[]
  labelId?: string
  page?: number
  size?: number
}
