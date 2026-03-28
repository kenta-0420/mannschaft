export interface DigestConfigResponse {
  id: number
  scheduleType: string
  scheduleTime: string
  scheduleDayOfWeek: number | null
  lastExecutedAt: string | null
  timezone: string
  digestStyle: string
  includeReactions: boolean
  includePolls: boolean
  includeDiffFromPrevious: boolean
  autoPublish: boolean
  stylePresets: string | null
  minPostsThreshold: number
  maxPostsPerDigest: number
  contentMaxChars: number
  language: string | null
  customPromptSuffix: string | null
  autoTagIds: string | null
  isEnabled: boolean
}

export interface DigestConfigRequest {
  scopeType?: string
  scopeId: number
  scheduleType?: string
  scheduleTime?: string
  scheduleDayOfWeek?: number
  digestStyle?: string
  autoPublish?: boolean
  stylePresets?: string
  includeReactions?: boolean
  includePolls?: boolean
  includeDiffFromPrevious?: boolean
  minPostsThreshold?: number
  maxPostsPerDigest?: number
  timezone?: string
  contentMaxChars?: number
  language?: string
  customPromptSuffix?: string
  autoTagIds?: number[]
}

export interface DigestGenerateRequest {
  scopeType?: string
  scopeId: number
  periodStart: string
  periodEnd: string
  digestStyle?: string
  customPromptSuffix?: string
  presetName?: string
}

export interface DigestGenerateResponse {
  id: number
  status: string
  estimatedPostCount: number
  aiQuota: AiQuotaResponse | null
}

export interface DigestDetailResponse {
  id: number
  scopeType: string
  scopeId: number
  periodStart: string
  periodEnd: string
  postCount: number
  digestStyle: string
  generatedTitle: string | null
  generatedBody: string | null
  generatedExcerpt: string | null
  aiModel: string | null
  aiInputTokens: number
  aiOutputTokens: number
  status: string
  blogPostId: number | null
  triggeredBy: { id: number; displayName: string } | null
  createdAt: string
}

export interface DigestSummaryResponse {
  id: number
  periodStart: string
  periodEnd: string
  postCount: number
  digestStyle: string
  generatedTitle: string | null
  status: string
  blogPostId: number | null
  createdAt: string
}

export interface DigestEditRequest {
  generatedTitle?: string
  generatedBody?: string
  generatedExcerpt?: string
}

export interface DigestPublishRequest {
  title?: string
  body?: string
  tagIds?: number[]
  visibility?: string
}

export interface DigestPublishResponse {
  digestId: number
  blogPostId: number
  blogPostSlug: string
  status: string
  staleWarning: { editedSinceGeneration: number; deletedSinceGeneration: number } | null
}

export interface DigestRegenerateRequest {
  digestStyle?: string
  customPromptSuffix?: string
}

export interface AiQuotaResponse {
  enabled: boolean
  used: number
  limit: number
  remaining: number
}
