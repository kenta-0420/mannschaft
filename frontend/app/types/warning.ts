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

// ViolationResponse は admin-report.ts で定義（重複を避けるためここでは再エクスポート）
export type { ViolationResponse } from './admin-report'

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
