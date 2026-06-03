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
 * グループ構成メンバー参照（Phase 3: 名寄せ）。
 * groupCount > 1 のときに groupMembers 配列に含まれる。
 */
export interface InboxItemRef {
  sourceType: InboxSourceType
  sourceId: number
}

/**
 * インボックスアイテム（InboxItemDto）。
 * id は "{sourceType}:{sourceId}" の複合論理キー。
 *
 * Phase 3 追加フィールド:
 *   canonicalRef  — BE が付与する正規化キー（FE では基本未使用）
 *   groupCount    — 畳んだ件数（1 なら単独）
 *   groupMembers  — 畳んだ全構成の triage キー（groupCount > 1 のとき複数）
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
  /** Phase 3: BE 正規化キー（FE では表示に使わない）。省略時は undefined。 */
  canonicalRef?: string
  /** Phase 3: 畳んだ件数（1 = 単独、2 以上 = グループカード）。省略時は 1 とみなす。 */
  groupCount?: number
  /** Phase 3: グループ構成メンバー（groupCount > 1 のとき bulk triage のキーとして使用）。 */
  groupMembers?: InboxItemRef[]
  /** Phase 3 (wave3b): 自動ラベリング提案（最大1件。既付与・条件外の場合は BE が抑制済み）。 */
  suggestedLabels?: SuggestedLabel[]
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

// ─────────────────────────────────────────────
// Phase 3 (wave3b): 自動ラベリング提案型
// ─────────────────────────────────────────────

/**
 * 自動ラベリングの suggestionKey 列挙値。
 * BE: com.mannschaft.app.inbox.autolabel.SuggestionKey
 */
export type InboxSuggestionKey = 'REPLY_NEEDED' | 'ACTION_NEEDED' | 'URGENT' | 'READ_LATER'

/**
 * 自動ラベリング提案 DTO（InboxItemDto.SuggestedLabelDto に対応）。
 * existingLabelId が非 null の場合、既にラベルが付与済みのため FE は表示しない（BE が抑制済み）。
 */
export interface SuggestedLabel {
  suggestionKey: InboxSuggestionKey
  color: string
  existingLabelId: string | null
}

// ─────────────────────────────────────────────
// Phase 2: ラベル CRUD ペイロード型
// ─────────────────────────────────────────────

/** ラベル作成リクエスト。 */
export interface CreateLabelPayload {
  name: string
  color?: string
  icon?: string
}

/** ラベル更新リクエスト。 */
export interface UpdateLabelPayload {
  name?: string
  color?: string
  icon?: string
  sortOrder?: number
}

/** ラベル一覧レスポンス。 */
export interface InboxLabelListResponse {
  data: InboxLabel[]
}

/** ラベル単体レスポンス。 */
export interface InboxLabelResponse {
  data: InboxLabel
}

// ─────────────────────────────────────────────
// Phase 2: bulk 操作型
// ─────────────────────────────────────────────

/** bulk 操作の種別。 */
export type InboxBulkAction = 'ARCHIVE' | 'UNARCHIVE' | 'SNOOZE' | 'LABEL_ADD'

/** bulk 操作の対象アイテム。 */
export interface InboxBulkItem {
  sourceType: InboxSourceType
  sourceId: number
}

/** POST /api/v1/inbox/bulk リクエスト。 */
export interface InboxBulkPayload {
  action: InboxBulkAction
  items: InboxBulkItem[]
  snoozedUntil?: string
  labelId?: string
}

/** POST /api/v1/inbox/bulk レスポンス。 */
export interface InboxBulkResponse {
  data: {
    processed: number
    skipped: number
  }
}
