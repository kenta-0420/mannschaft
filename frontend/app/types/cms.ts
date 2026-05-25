export type BlogPostStatus = 'DRAFT' | 'PUBLISHED' | 'SCHEDULED' | 'ARCHIVED'
export type BlogPostScope = 'TEAM' | 'ORGANIZATION' | 'PUBLIC' | 'PERSONAL'

export interface BlogPostContent {
  title: string
  slug: string
  body: string | null
  excerpt: string | null
  coverImageUrl: string | null
}

export interface BlogPostMeta {
  status: BlogPostStatus
  visibility: string | null
  postType: string | null
}

export interface BlogPostAudit {
  publishedAt: string | null
  createdAt: string
  updatedAt: string
  createdBy: { id: number; displayName: string; avatarUrl: string | null } | null
}

export interface BlogPostScope2 {
  teamId: number | null
  organizationId: number | null
  userId: number | null
}

export interface BlogPostStats {
  viewCount: number
  readingTimeMinutes: number | null
  mitayo: boolean
  mitayoCount: number
}

export interface BlogPostResponse {
  id: number
  content?: BlogPostContent
  meta?: BlogPostMeta
  audit?: BlogPostAudit
  scope?: BlogPostScope2
  stats?: BlogPostStats
  author: { id: number; displayName: string; avatarUrl: string | null }
  tags: BlogTag[]
  seriesId: number | null
  seriesName: string | null
  seriesOrder: number | null
  scheduledAt: string | null
  /** @deprecated scopeType は scope?.organizationId / scope?.teamId / scope?.userId で判定 */
  scopeType?: BlogPostScope
  /** @deprecated scopeId は scope?.organizationId / scope?.teamId / scope?.userId で判定 */
  scopeId?: number | null
}

export interface BlogReactionResponse {
  blogPostId: number
  mitayo: boolean
  mitayoCount: number
}

export interface BlogTag {
  id: number
  name: string
  postCount: number
}

export interface BlogSeries {
  id: number
  title: string
  description: string | null
  postCount: number
  createdAt: string
}

export interface BlogRevision {
  id: number
  title: string
  editedAt: string
  editedBy: { id: number; displayName: string }
}
