export type TimelineScopeType = 'TEAM' | 'ORGANIZATION' | 'PUBLIC'
export type TimelinePostStatus = 'PUBLISHED' | 'DRAFT' | 'SCHEDULED'
export type TimelineAttachmentType = 'IMAGE' | 'VIDEO_LINK' | 'LINK_PREVIEW'
export type PostedAsType = 'USER' | 'TEAM' | 'ORGANIZATION' | 'SOCIAL_PROFILE'

export const PRESET_EMOJIS = ['👍', '👏', '🙏', '😊', '❤️', '🔥', '🙇'] as const
export type PresetEmoji = (typeof PRESET_EMOJIS)[number]

export const CONTENT_TRUNCATE_LENGTH = 500

export interface TimelineUser {
  id: number
  displayName: string
  avatarUrl: string | null
}

export interface PostedAs {
  type: PostedAsType
  id: number
  name?: string
  logoUrl?: string
  handle?: string
  displayName?: string
  avatarUrl?: string
}

export interface TimelineAttachment {
  id: number
  attachmentType: TimelineAttachmentType
  url?: string
  thumbnailUrl?: string
  imageWidth?: number
  imageHeight?: number
  videoUrl?: string
  videoThumbnailUrl?: string
  videoTitle?: string
  linkUrl?: string
  ogTitle?: string
  ogDescription?: string
  ogImageUrl?: string
  ogSiteName?: string
}

export interface TimelinePollOption {
  id: number
  optionText: string
  voteCount: number
  sortOrder: number
}

export interface TimelinePoll {
  id: number
  question: string
  options: TimelinePollOption[]
  totalVoteCount: number
  myVoteOptionId: number | null
  isClosed: boolean
  expiresAt: string | null
}

export interface RepostOf {
  id: number | null
  deleted?: boolean
  contentPreview?: string
  user?: TimelineUser
  postedAs?: PostedAs | null
}

export interface TimelinePostResponse {
  id: number
  scopeType: TimelineScopeType
  scopeId: number
  user: TimelineUser | null
  postedAs: PostedAs | null
  parentId: number | null
  content: string | null
  isPinned: boolean
  isBookmarked: boolean
  isEdited: boolean
  isTruncated: boolean
  reactionCount: number
  replyCount: number
  attachmentCount: number
  repostCount: number
  attachments: TimelineAttachment[]
  myReactions: string[]
  reactionSummary: Record<string, number>
  repostOf: RepostOf | null
  poll: TimelinePoll | null
  status: TimelinePostStatus
  scheduledAt: string | null
  createdAt: string
  updatedAt: string
}

export interface TimelineFeedResponse {
  data: {
    pinned: TimelinePostResponse[]
    posts: TimelinePostResponse[]
  }
  meta: {
    nextCursor: number | null
    limit: number
    hasNext: boolean
  }
}

export interface TimelinePostDetailResponse {
  data: TimelinePostResponse & {
    recentReplies: TimelinePostResponse[]
  }
}

export interface CreateTimelinePostRequest {
  scopeType: TimelineScopeType
  scopeId?: number
  postedAsType?: PostedAsType
  postedAsId?: number
  socialProfileId?: number
  content?: string
  status?: TimelinePostStatus
  scheduledAt?: string
  videoUrls?: string[]
  poll?: {
    question: string
    options: string[]
    expiresAt?: string
  }
}

export interface UpdateTimelinePostRequest {
  content?: string
  status?: TimelinePostStatus
  scheduledAt?: string
}

export interface TimelineReplyRequest {
  content: string
}

export interface RepostRequest {
  content?: string
}

export interface TimelineEditHistory {
  id: number
  contentBefore: string
  editedAt: string
}

export interface ReactionDetail {
  emoji: string
  count: number
  users: TimelineUser[]
}
