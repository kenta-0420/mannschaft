/**
 * F02.5 行動メモ機能の型定義。
 *
 * 設計書 §4 に基づき、Backend は JSON で一部スネークケースを使うが、
 * フロント側ではキャメルケースに正規化したものを利用する。
 * 変換は {@link useActionMemoApi} 内で行う。
 */

// === Mood ===

/** 5択リテラルユニオン。NULL は「未選択」を表す */
export type Mood = 'GREAT' | 'GOOD' | 'OK' | 'TIRED' | 'BAD'

// === Tag ===

/**
 * メモに紐付くタグサマリ（埋め込み用）。
 * Phase 4 で本格的な CRUD UI を作るが、Phase 1 ではメモ表示時の参照型として用意する。
 */
export interface ActionMemoTag {
  id: number
  name: string
  /** HEX カラー。NULL = デフォルト色 */
  color: string | null
  /** 論理削除済みタグかどうか。過去メモ表示時にバッジ色を変える */
  deleted: boolean
}

// === Memo ===

/**
 * 行動メモ本体。
 * Backend の {@code action_memos} テーブルに対応。
 */
export interface ActionMemo {
  id: number
  /** ISO 日付（YYYY-MM-DD） */
  memoDate: string
  content: string
  /** 設定 OFF のユーザーは常に null */
  mood: Mood | null
  relatedTodoId: number | null
  /** 終業投稿がまだなら null */
  timelinePostId: number | null
  tags: ActionMemoTag[]
  /** ISO LocalDateTime（例 "2026-04-09T08:32:14"） */
  createdAt: string
  updatedAt?: string
}

// === Settings ===

/**
 * ユーザー個別の行動メモ設定。
 * レコード未作成のユーザーはサーバー側でデフォルト値が返る。
 */
export interface ActionMemoSettings {
  moodEnabled: boolean
}

// === Request payloads ===

export interface CreateActionMemoPayload {
  content: string
  /** 省略時はサーバー側で JST 今日が自動セットされる */
  memoDate?: string
  mood?: Mood | null
  relatedTodoId?: number | null
  tagIds?: number[]
}

export interface UpdateActionMemoPayload {
  content?: string
  memoDate?: string
  mood?: Mood | null
  relatedTodoId?: number | null
  tagIds?: number[]
}

// === List response ===

export interface ActionMemoListResponse {
  data: ActionMemo[]
  /** 次ページがある場合のカーソル。最終ページなら null */
  nextCursor: string | null
}

// === List query params ===

export interface ListActionMemoParams {
  /** 単日指定（YYYY-MM-DD）。`from`/`to` と排他 */
  date?: string
  /** 期間指定（YYYY-MM-DD）開始 */
  from?: string
  /** 期間指定（YYYY-MM-DD）終了 */
  to?: string
  tagId?: number
  cursor?: string
  /** デフォルト 50、最大 200 */
  limit?: number
}

// === Error helpers ===

/**
 * 429 レートリミット応答に付与されるカスタムエラー。
 * `Retry-After` ヘッダー（秒）を呼び出し側に伝えるために使う。
 */
export interface ActionMemoRateLimitError extends Error {
  status: 429
  retryAfterSeconds: number | null
}
