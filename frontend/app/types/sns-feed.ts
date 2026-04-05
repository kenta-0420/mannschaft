export interface SnsFeedConfigResponse {
  id: number
  scopeType: string
  scopeId: number
  provider: string
  accountUsername: string
  displayCount: number
  isActive: boolean
  configuredBy: number
  createdAt: string
  updatedAt: string
}

export interface CreateSnsFeedConfigRequest {
  provider?: string
  accountUsername?: string
  accessToken?: string
  displayCount?: number
}

export interface UpdateSnsFeedConfigRequest {
  accountUsername?: string
  accessToken?: string
  displayCount?: number
  isActive?: boolean
}

export interface SnsFeedPreviewResponse {
  provider: string
  accountUsername: string
  items: FeedItem[]
}

export interface FeedItem {
  postId: string
  imageUrl: string | null
  caption: string | null
  permalink: string | null
  postedAt: string
}
