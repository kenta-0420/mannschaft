import type { components } from '~/types/generated'

// BE の TranslationStatus enum（backend/.../translation/TranslationStatus.java）に一致。
// APPROVED / STALE / REJECTED は実装に存在しない架空の状態のため含めない。
export type TranslationStatus = 'DRAFT' | 'IN_REVIEW' | 'PUBLISHED' | 'NEEDS_UPDATE'
export type TranslationSourceType = 'BLOG_POST' | 'KNOWLEDGE_BASE' | 'ANNOUNCEMENT' | 'EVENT' | 'FORM'

export interface TranslationResponse {
  id: number
  sourceType: TranslationSourceType
  sourceId: number
  sourceTitle: string
  sourceLanguage: string
  targetLanguage: string
  status: TranslationStatus
  translatedTitle: string | null
  translatedContent: string | null
  assignedTo: { id: number; displayName: string } | null
  createdAt: string
  updatedAt: string
}

export interface TranslationListResponse {
  content: TranslationResponse[]
  totalElements: number
  totalPages: number
  number: number  // current page (0-indexed)
  size: number
}

export interface CreateTranslationRequest {
  sourceType: TranslationSourceType
  sourceId: number
  targetLanguage: string
  assignedToUserId?: number
}

// BE の実応答は平坦な camelCase（totalTranslations/draft/inReview/published/needsUpdate）。
// byStatus のような入れ子オブジェクトは存在しない。生成型（openapi.json 由来）を正とする。
export type TranslationDashboard = components['schemas']['TranslationDashboardResponse']
