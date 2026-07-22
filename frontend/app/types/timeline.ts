export type TimelineScopeType = 'TEAM' | 'ORGANIZATION' | 'PUBLIC' | 'VILLAGE'
export type TimelinePostStatus = 'PUBLISHED' | 'DRAFT' | 'SCHEDULED'
export type TimelineAttachmentType = 'IMAGE' | 'VIDEO_FILE' | 'VIDEO_LINK' | 'LINK_PREVIEW'
export type VideoProcessingStatus = 'PENDING' | 'PROCESSING' | 'READY' | 'FAILED'
export type PostedAsType = 'USER' | 'TEAM' | 'ORGANIZATION' | 'SOCIAL_PROFILE'

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

/** 添付ファイル本体（file_key はR2生キー。表示URLは image/video 側の署名URLを使う）。 */
export interface TimelineAttachmentFile {
  fileKey?: string
  originalFilename?: string
  fileSize?: number
  mimeType?: string
}

/**
 * 画像添付。url/thumbnailUrl はBEが MediaUrlResolver で解決した署名付き表示URL（issue #2424）。
 * DBには生キーしか無いためBEが署名して返す（FEはR2を署名できない）。画像は別サムネイルを
 * 持たないため thumbnailUrl は url と同一値。
 */
export interface TimelineAttachmentImage {
  imageWidth?: number
  imageHeight?: number
  url?: string
  thumbnailUrl?: string
}

/** 動画添付（videoUrl/videoThumbnailUrl は署名URLまたは外部URL）。 */
export interface TimelineAttachmentVideo {
  videoUrl?: string
  videoThumbnailUrl?: string
  videoTitle?: string
  videoThumbnailKey?: string
  videoDurationSeconds?: number
  videoCodec?: string
  videoWidth?: number
  videoHeight?: number
  videoProcessingStatus?: VideoProcessingStatus
}

/** リンクプレビュー添付（OGP）。 */
export interface TimelineAttachmentLink {
  linkUrl?: string
  ogTitle?: string
  ogDescription?: string
  ogImageUrl?: string
  ogSiteName?: string
}

/**
 * タイムライン投稿の添付。BE の {@code TimelineAttachmentResponse}（ネスト形 file/image/video/link）と
 * 1:1 対応する。生成型 {@code components['schemas']['TimelineAttachmentResponse']} と同形。
 */
export interface TimelineAttachment {
  id: number
  attachmentType: TimelineAttachmentType
  sortOrder?: number
  file?: TimelineAttachmentFile
  image?: TimelineAttachmentImage
  video?: TimelineAttachmentVideo
  link?: TimelineAttachmentLink
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

export interface PostScopeDto {
  scopeType: TimelineScopeType
  scopeId: string
  /** 投稿元スコープ名（個人集約タイムラインで BE から enrich。TEAM/ORGANIZATION のみ・それ以外は null） */
  name?: string | null
  /** 投稿元スコープ slug（遷移先 /teams/{slug} or /organizations/{slug} の生成に使う。null 可） */
  slug?: string | null
}

export interface PostAuthorDto {
  userId: number
  socialProfileId: number | null
  postedAsType: string | null
  postedAsId: number | null
}

export interface PostContentDto {
  content: string | null
  parentId: number | null
  repostOfId: number | null
  status: TimelinePostStatus
  scheduledAt: string | null
  isPinned: boolean
}

export interface PostStatsDto {
  repostCount: number
  reactionCount: number
  replyCount: number
  attachmentCount: number
  editCount: number
}

export interface PostAuditDto {
  createdAt: string
  updatedAt: string
}

export interface TimelinePostResponse {
  id: number
  scope: PostScopeDto
  author: PostAuthorDto
  content: PostContentDto
  stats: PostStatsDto
  audit: PostAuditDto
  // --- フィード固有の enrichment フィールド（バックエンドから付加されるが PostResponse 外） ---
  user: TimelineUser | null
  postedAs: PostedAs | null
  isBookmarked: boolean
  isEdited: boolean
  isTruncated: boolean
  mitayo: boolean
  mitayoCount: number
  attachments: TimelineAttachment[]
  repostOf: RepostOf | null
  poll: TimelinePoll | null
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

