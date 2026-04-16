/**
 * F01.5 チーム間フレンド関係 型定義（フォロー・フレンド関係・公開設定）。
 *
 * バックエンド DTO（{@code com.mannschaft.app.social.dto}）に対応する
 * TypeScript 型。JSON プロパティ名は snake_case 記法を維持する
 * （バックエンドの設計書 §5 に合わせるため）。
 */

/** 過去転送投稿の扱い（フォロー解除時） */
export type PastForwardHandling = 'KEEP' | 'SOFT_DELETE' | 'ARCHIVE'

/** 一覧系の共通ページネーションメタ情報（オフセットベース） */
export interface FriendsPagination {
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

/** フレンドチーム一覧の 1 エントリ */
export interface TeamFriendView {
  /** team_friends.id */
  team_friend_id: number
  /** 相手チーム ID（閲覧者視点） */
  friend_team_id: number
  /** 相手チーム名（非公開時は空になり得る） */
  friend_team_name: string | null
  /** フレンド関係の公開フラグ */
  is_public: boolean
  /** フレンド関係成立日時（ISO 8601） */
  established_at: string
}

/** フレンドチーム一覧レスポンス */
export interface TeamFriendListResponse {
  /** フレンドチーム一覧 */
  data: TeamFriendView[]
  /** ページング情報 */
  pagination: FriendsPagination
}

/** 他チームフォロー用リクエスト */
export interface FollowTeamRequest {
  /** フォロー先チーム ID */
  target_team_id: number
  /** 挨拶コメント（任意、最大 300 文字） */
  comment?: string
}

/** チーム間フォローのレスポンス */
export interface FollowTeamResponse {
  /** 新規に生成された follows.id（NOWAIT 競合時は null） */
  follow_id: number | null
  /** フォロー元チーム ID（自チーム） */
  follower_team_id: number
  /** フォロー先チーム ID */
  followed_team_id: number
  /** 相互フォロー成立フラグ（true でフレンド関係成立） */
  mutual: boolean
  /** フレンド関係 ID（相互成立時のみ） */
  team_friend_id?: number | null
  /** フレンド関係成立日時（相互成立時のみ） */
  established_at?: string | null
  /** フレンド関係の公開フラグ（相互成立時のみ） */
  is_public?: boolean | null
  /** follows レコード作成日時 */
  created_at: string
  /** NOWAIT 競合で 202 Accepted 時の再試行秒数（通常は null） */
  retry_after_seconds?: number | null
}

/** フォロー解除リクエスト */
export interface UnfollowRequest {
  /** 過去転送投稿の扱い */
  past_forward_handling: PastForwardHandling
}

/** フレンド関係の公開設定変更リクエスト */
export interface SetVisibilityRequest {
  /** 公開フラグ（true で公開、false で非公開） */
  is_public: boolean
}
