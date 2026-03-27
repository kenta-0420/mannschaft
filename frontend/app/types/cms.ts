export type BlogPostStatus = 'DRAFT' | 'PUBLISHED' | 'SCHEDULED' | 'ARCHIVED'
export type BlogPostScope = 'TEAM' | 'ORGANIZATION' | 'PUBLIC' | 'PERSONAL'

export interface BlogPostResponse {
  id: number
  slug: string
  title: string
  body: string | null
  excerpt: string | null
  coverImageUrl: string | null
  status: BlogPostStatus
  scopeType: BlogPostScope
  scopeId: number | null
  author: { id: number; displayName: string; avatarUrl: string | null }
  tags: Array<{ id: number; name: string }>
  seriesId: number | null
  seriesName: string | null
  seriesOrder: number | null
  publishedAt: string | null
  scheduledAt: string | null
  viewCount: number
  createdAt: string
  updatedAt: string
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
