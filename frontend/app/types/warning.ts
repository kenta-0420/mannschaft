export interface WarningReReviewResponse {
  id: number
  userId: number
  reportId: number
  actionId: number
  reason: string
  status: string
  adminReviewedBy: number | null
  adminReviewNote: string | null
  adminReviewedAt: string | null
  escalationReason: string | null
  systemAdminReviewedBy: number | null
  systemAdminReviewNote: string | null
  systemAdminReviewedAt: string | null
  createdAt: string
}

export interface CreateReReviewRequest {
  reportId: number
  reason?: string
}

export interface SelfCorrectRequest {
  correctionNote?: string
}

export interface ViolationResponse {
  id: number
  userId: number
  reportId: number
  actionId: number
  violationType: string
  reason: string
  expiresAt: string | null
  isActive: boolean
  createdAt: string
}

export interface YabaiUnflagResponse {
  id: number
  userId: number
  reason: string
  status: string
  reviewedBy: number | null
  reviewNote: string | null
  reviewedAt: string | null
  nextEligibleAt: string | null
  createdAt: string
}

export interface CreateUnflagRequest {
  reason?: string
}
