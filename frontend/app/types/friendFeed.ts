/**
 * F01.5 管理者フィード API 型定義。
 *
 * GET /api/v1/teams/{id}/friend-feed のレスポンス・クエリパラメータ型。
 */

export interface FriendFeedSourceTeam {
  id: number
  name: string
}

export interface FriendFeedForwardStatus {
  isForwarded: boolean
  forwardId: number | null
  forwardedAt: string | null
}

export interface FriendFeedPost {
  postId: number
  sourceTeam: FriendFeedSourceTeam
  content: string
  receivedAt: string
  forwardStatus: FriendFeedForwardStatus
}

export interface FriendFeedMeta {
  nextCursor: number | null
  limit: number
  hasNext: boolean
}

export interface FriendFeedResponse {
  data: FriendFeedPost[]
  meta: FriendFeedMeta
}

export interface FriendFeedParams {
  folderId?: number
  sourceTeamId?: number
  forwardedOnly?: boolean
  cursor?: number
  limit?: number
}
