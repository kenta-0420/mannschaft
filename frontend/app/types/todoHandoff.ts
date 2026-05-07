/**
 * F02.3.1 Phase 2 — TODO キャッチボール（引き渡し）型定義
 */

/** ユーザー要約（履歴行内のアバター列に使用） */
export interface HandoffUserSummary {
  userId: number
  displayName: string
}

/**
 * 履歴に保存されたラベル情報のスナップショット。
 * 元ラベルが削除済みの場合は {@code id} が NULL かつ {@code deleted=true}。
 */
export interface HandoffLabelInfo {
  id: number | null
  name: string | null
  bucket: string | null
  color: string | null
  deleted: boolean
}

/** キャッチボール実行リクエスト */
export interface HandoffRequest {
  /** 新しい assignees の userId 一覧（必須・1件以上） */
  toUserIds: number[]
  /** 新しいステータスラベル ID（必須） */
  statusLabelId: number
  /** 添えメッセージ（任意・500文字まで） */
  message?: string | null
}

/** キャッチボール履歴1行 */
export interface HandoffResponse {
  id: number
  fromUser: HandoffUserSummary
  fromAssignees: HandoffUserSummary[]
  toAssignees: HandoffUserSummary[]
  previousStatus: string
  previousStatusLabel: HandoffLabelInfo | null
  newStatus: string
  newStatusLabel: HandoffLabelInfo | null
  message: string | null
  createdAt: string
}

/** 履歴一覧 API のレスポンス */
export interface HandoffHistoryResponse {
  data: HandoffResponse[]
}

/** 引き渡し API のレスポンス */
export interface HandoffApiResponse {
  data: HandoffResponse
}
