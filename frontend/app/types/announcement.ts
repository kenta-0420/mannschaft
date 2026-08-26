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

export type AnnouncementVisibility = 'PUBLIC' | 'MEMBERS_AND_ABOVE' | 'SUPPORTERS_AND_ABOVE'

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
  scopeId: string
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
  /**
   * F09.17: 広告由来のお知らせかどうか。
   * true の場合は <AdLabelBadge /> の表示と景品表示法対応の挙動を有効化する。
   */
  isAdvertisement?: boolean
  /** F09.17: 広告主アカウント ID（{@link isAdvertisement} が true の場合のみ） */
  advertiserAccountId?: number
  /** F09.17: メッセージ型キャンペーン ID（UUID 文字列、{@link isAdvertisement} が true の場合のみ） */
  messagingCampaignId?: string
  /** F09.17: お知らせ枠経由の広告であることを明示する固定値 */
  channelType?: 'ANNOUNCEMENT'
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
  /** このリクエストで新たに既読化した件数（既読済みだったものは含まない） */
  markedCount: number
  /**
   * 1 リクエストの防御上限（500 件 × 20 チャンク = 10,000 件）に到達して打ち切り、
   * 未読が残っているか。true のとき「未読 0」と表示してはならない（#2530 ①）。
   */
  hasMoreUnread: boolean
}

export interface AnnouncementFeedParams {
  cursor?: number
  limit?: number
  includeRead?: boolean
  sourceType?: AnnouncementSourceType
}
