/**
 * マイスコープフォルダ関連の型定義。
 *
 * F15.2 のフォルダ CRUD 用型に加え、F15.3（マイスコープフォルダ統合UX）で
 * 追加された未分類フォルダ・アイコン・通知集計・一括振り分け関連の型を定義する。
 */

/** フォルダのスコープ種別 */
export type ScopeType = 'TEAM' | 'ORGANIZATION'

/**
 * フォルダレスポンス本体。
 *
 * F15.2 既存フィールド: id, name, color, sortOrder, itemScopeIds。
 * F15.3 追加フィールド: isDefault, icon, notificationUnreadCount（オプショナル）。
 *
 * 旧コードとの後方互換のため `ScopeFolder` という名前は維持する。
 * Backend DTO `ScopeFolderResponse` と意味的に同一であり、エイリアスを提供する。
 */
export interface ScopeFolder {
  id: number
  name: string
  color: string | null
  /** PrimeIcons のアイコン名（例: `pi-users`）。F15.3 追加。 */
  icon?: string | null
  /** 「未分類」フォルダなら true。改名・削除不可。F15.3 追加。 */
  isDefault?: boolean
  sortOrder: number
  itemScopeIds: string[]
  /** フォルダ別未読件数。F15.3 で追加。1 クエリ集計のためレスポンスに同梱可能。 */
  notificationUnreadCount?: number
}

/** Backend DTO 名と整合させたエイリアス。新規コードはこの型名を推奨。 */
export type ScopeFolderResponse = ScopeFolder

/** アイテムの割当経路（監査用）。 */
export type AssignedVia = 'INVITE' | 'MANUAL' | 'MIGRATION' | 'DEFAULT'

export interface CreateFolderRequest {
  name: string
  color?: string | null
  /** F15.3 追加。PrimeIcons のアイコン名。 */
  icon?: string | null
}

export interface UpdateFolderRequest {
  name: string
  color?: string | null
  /** F15.3 追加。アイコン名。 */
  icon?: string | null
}

export interface ReorderFoldersRequest {
  orderedIds: number[]
}

/** 一括振り分け（POST /api/v1/me/scope-folders/items/bulk-assign） */
export interface BulkAssignRequest {
  folderId: number
  scopeIds: string[]
  scopeType: ScopeType
}

export interface BulkAssignResponse {
  assignedCount: number
  skippedCount: number
  errors: string[]
}

/** フォルダ別未読件数（GET /notifications/summary） */
export interface FolderNotificationSummary {
  folderId: number
  unreadCount: number
}
