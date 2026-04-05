export interface FeedbackResponse {
  id: number
  scopeType: string
  scopeId: number
  category: string
  title: string
  body: string
  isAnonymous: boolean
  submittedBy: number
  status: string
  adminResponse: string | null
  respondedBy: number | null
  respondedAt: string | null
  isPublicResponse: boolean
  voteCount: number
  createdAt: string
  updatedAt: string
}

export interface CreateFeedbackRequest {
  scopeType: string
  scopeId: number
  category: string
  title?: string
  body: string
  isAnonymous?: boolean
}
