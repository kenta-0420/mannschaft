export type TranslationStatus = 'DRAFT' | 'IN_REVIEW' | 'APPROVED' | 'PUBLISHED' | 'STALE' | 'REJECTED'
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

export interface TranslationDashboard {
  totalCount: number
  byStatus: Record<TranslationStatus, number>
  byLanguage: Record<string, number>
}
