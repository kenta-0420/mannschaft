/**
 * F02.6 お知らせウィジェット — 型定義
 */

export type AnnouncementScopeType = 'TEAM' | 'ORGANIZATION'

export type AnnouncementSourceType =
  | 'BLOG_POST'
  | 'BULLETIN_THREAD'
  | 'TIMELINE_POST'
  | 'CIRCULATION'
  | 'SURVEY'

export type AnnouncementPriority = 'URGENT' | 'IMPORTANT' | 'NORMAL'

export type AnnouncementVisibility = 'PUBLIC' | 'MEMBERS_ONLY' | 'SUPPORTERS_AND_ABOVE'

export interface AnnouncementAuthor {
  id: number
  displayName: string
  avatarUrl: string | null
}

export interface AnnouncementSourceMeta {
  // BLOG_POST
  postType?: string
  coverImageUrl?: string
  // BULLETIN_THREAD
  categoryName?: string
  replyCount?: number
  // TIMELINE_POST
  attachmentCount?: number
  reactionCount?: number
  // CIRCULATION
  circulationMode?: string
  dueDate?: string
  stampedCount?: number
  totalRecipientCount?: number
  // SURVEY
  responseCount?: number
  targetCount?: number
  expiresAt?: string
}

export interface AnnouncementFeedItem {
  id: number
  scopeType: AnnouncementScopeType
  scopeId: number
  sourceType: AnnouncementSourceType
  sourceId: number
  sourceUrl: string
  title: string
  excerpt: string | null
  priority: AnnouncementPriority
  isPinned: boolean
  pinnedAt: string | null
  visibility: AnnouncementVisibility
  author: AnnouncementAuthor | null
  sourceMeta: AnnouncementSourceMeta | null
  isRead: boolean
  startsAt: string | null
  expiresAt: string | null
  createdAt: string
}

export interface AnnouncementFeedMeta {
  nextCursor: number | null
  limit: number
  unreadCount: number
  totalCount: number
  hasNext: boolean
}

export interface AnnouncementFeedResponse {
  data: AnnouncementFeedItem[]
  meta: AnnouncementFeedMeta
}

export interface CreateAnnouncementRequest {
  sourceType: AnnouncementSourceType
  sourceId: number
  priority?: AnnouncementPriority
  startsAt?: string | null
  expiresAt?: string | null
}

export interface CreateAnnouncementResponse {
  id: number
  sourceType: AnnouncementSourceType
  sourceId: number
  priority: AnnouncementPriority
  isPinned: boolean
  createdAt: string
}

export interface TogglePinRequest {
  pinned: boolean
}

export interface TogglePinResponse {
  id: number
  isPinned: boolean
  pinnedAt: string | null
  pinnedBy: number | null
}

export interface MarkReadResponse {
  id: number
  isRead: boolean
  readAt: string
}

export interface MarkAllReadResponse {
  markedCount: number
}

export interface AnnouncementFeedParams {
  cursor?: number
  limit?: number
  includeRead?: boolean
  sourceType?: AnnouncementSourceType
}
