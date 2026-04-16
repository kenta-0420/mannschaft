/**
 * F01.5 フレンドコンテンツ転送 型定義。
 *
 * バックエンド DTO（{@code com.mannschaft.app.social.dto.ForwardRequest} 等）
 * に対応する TypeScript 型。JSON プロパティ名は snake_case 記法を維持する。
 *
 * Phase 1 では {@code target='MEMBER'} のみ受理される。
 * {@code MEMBER_AND_SUPPORTER} は Phase 3 で解禁予定。
 */

/** フレンド転送配信範囲（Phase 1 は MEMBER のみ利用可） */
export type ForwardTarget = 'MEMBER' | 'MEMBER_AND_SUPPORTER'

/** 転送リクエスト */
export interface ForwardRequest {
  /** 配信範囲（Phase 1 は MEMBER 固定） */
  target: ForwardTarget
  /** 管理者コメント（任意、最大 500 文字） */
  comment?: string
}

/** 転送実行結果レスポンス */
export interface ForwardResponse {
  /** friend_content_forwards.id */
  forward_id: number
  /** 転送元投稿 ID（フレンドチーム側の原投稿） */
  source_post_id: number
  /** 転送で生成された自チーム内の投稿 ID */
  forwarded_post_id: number
  /** 配信範囲 */
  target: ForwardTarget
  /** 転送実行日時（ISO 8601） */
  forwarded_at: string
}

/** 逆転送履歴 View（自チーム投稿が他フレンドに転送された履歴の 1 件） */
export interface FriendForwardExportView {
  /** friend_content_forwards.id */
  forward_id: number
  /** 転送元投稿 ID（自チーム発信投稿） */
  source_post_id: number
  /**
   * 転送先チーム名。
   * team_friends.is_public = FALSE の場合は「匿名チーム」に匿名化される。
   */
  forwarding_team_name: string
  /** 配信範囲 */
  target: ForwardTarget
  /** 転送実行日時（ISO 8601） */
  forwarded_at: string
  /** 取消済みフラグ */
  is_revoked: boolean
}

/** 逆転送履歴のページネーション情報（オフセットベース） */
export interface FriendForwardExportPagination {
  /** 現在のページ番号（0 始まり） */
  page: number
  /** 1 ページあたりの件数 */
  size: number
  /** 総件数 */
  total_elements: number
  /** 総ページ数 */
  total_pages: number
  /** 次ページが存在するか */
  has_next: boolean
}

/** 逆転送履歴一覧レスポンス */
export interface FriendForwardExportListResponse {
  /** 逆転送履歴一覧 */
  data: FriendForwardExportView[]
  /** ページング情報 */
  pagination: FriendForwardExportPagination
}
