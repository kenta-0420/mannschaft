import type { PageMeta } from './api'

// ─────────────────────────────────────────
// フレンドチーム
// ─────────────────────────────────────────

export interface TeamFriendView {
  teamFriendId: number
  friendTeamId: number
  friendTeamName: string
  isPublic: boolean
  establishedAt: string // ISO 8601
}

export interface TeamFriendListResponse {
  data: TeamFriendView[]
  pagination: PageMeta & { hasNext: boolean }
}

// ─────────────────────────────────────────
// フォロー / アンフォロー
// ─────────────────────────────────────────

export interface FollowTeamRequest {
  targetTeamId: number
}

export interface FollowTeamResponse {
  followId: number | null
  followerTeamId: number
  followedTeamId: number
  mutual: boolean
  teamFriendId: number | null
  establishedAt: string | null
  isPublic: boolean | null
  createdAt: string
  retryAfterSeconds: number | null
}

export type PastForwardHandling = 'KEEP' | 'SOFT_DELETE' | 'ARCHIVE'

export interface UnfollowRequest {
  pastForwardHandling: PastForwardHandling
}

// ─────────────────────────────────────────
// 可視性設定
// ─────────────────────────────────────────

export interface SetVisibilityRequest {
  isPublic: boolean
}

// ─────────────────────────────────────────
// フォルダ
// ─────────────────────────────────────────

export interface TeamFriendFolderView {
  id: number
  name: string
  description: string | null
  color: string | null
  sortOrder: number
  isDefault: boolean
  memberCount: number
  createdAt: string
  updatedAt: string
}

export interface CreateFolderRequest {
  name: string
  description?: string
  color?: string
}

export interface UpdateFolderRequest {
  name?: string
  description?: string
  color?: string
}

export interface AddMemberRequest {
  teamFriendId: number
}

// ─────────────────────────────────────────
// フォワード（転送）
// ─────────────────────────────────────────

/**
 * 配信範囲。
 * Phase 1 では MEMBER のみ受理する（MEMBER_AND_SUPPORTER は 400 エラー）。
 */
export type ForwardTarget = 'MEMBER' | 'MEMBER_AND_SUPPORTER'

export interface ForwardRequest {
  target: ForwardTarget
  comment?: string
}

export interface ForwardResponse {
  forwardId: number
  sourcePostId: number
  forwardedPostId: number
  target: ForwardTarget
  forwardedAt: string
}

export interface FriendForwardExportView {
  forwardId: number
  sourcePostId: number
  forwardingTeamId: number
  forwardingTeamName: string
  target: ForwardTarget
  comment: string | null
  forwardedAt: string
  isRevoked: boolean
}

export interface FriendForwardExportListResponse {
  data: FriendForwardExportView[]
  pagination: PageMeta & { hasNext: boolean }
}
