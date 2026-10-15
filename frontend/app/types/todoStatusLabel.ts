/**
 * F02.3.1 — TODO カスタムステータスラベル型定義
 *
 * バックエンド設計書: docs/features/F02.3.1_todo_status_labels_and_handoff.md
 */

export type TodoStatusLabelScopeType = 'SYSTEM' | 'PERSONAL' | 'TEAM' | 'ORGANIZATION'
export type TodoStatusLabelBucket = 'OPEN' | 'IN_PROGRESS' | 'COMPLETED'

/**
 * カスタムステータスラベル本体
 */
export interface TodoStatusLabel {
  id: number
  scopeType: TodoStatusLabelScopeType
  scopeId: string | null
  name: string
  bucket: TodoStatusLabelBucket
  /** HEX カラー (#RRGGBB) — null の場合は bucket 既定色にフォールバック */
  color: string | null
  sortOrder: number
  isSystemDefault: boolean
  createdBy?: number | null
  createdAt: string
  updatedAt?: string
}

/**
 * TODO レスポンス内に埋め込まれるラベル情報（軽量版）
 */
export interface TodoStatusLabelInfo {
  id: number
  name: string
  bucket: TodoStatusLabelBucket
  color: string | null
}

export interface CreateTodoStatusLabelRequest {
  /** 1〜50 文字 */
  name: string
  bucket: TodoStatusLabelBucket
  /** HEX カラー (#RRGGBB) */
  color?: string
  sortOrder?: number
}

export interface UpdateTodoStatusLabelRequest {
  name?: string
  bucket?: TodoStatusLabelBucket
  color?: string
  sortOrder?: number
}

export interface TodoStatusLabelListResponse {
  data: TodoStatusLabel[]
}

export interface TodoStatusLabelResponse {
  data: TodoStatusLabel
}

/**
 * バケット既定色（label.color が null のときのフォールバック用）
 * SYSTEM 既定ラベル (V19.003) に揃える
 */
export const BUCKET_DEFAULT_COLOR: Record<TodoStatusLabelBucket, string> = {
  OPEN: '#94a3b8',
  IN_PROGRESS: '#3b82f6',
  COMPLETED: '#22c55e',
}

/**
 * SYSTEM 既定ラベル ID（V19.003 で固定挿入）
 */
export const SYSTEM_LABEL_ID = {
  OPEN: 1,
  IN_PROGRESS: 2,
  COMPLETED: 3,
} as const

/**
 * スコープあたりの最大ラベル数（SYSTEM を除く）
 */
export const LABEL_LIMIT_PER_SCOPE = 20
