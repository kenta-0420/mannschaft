export type PromotionStatus = 'DRAFT' | 'PENDING_APPROVAL' | 'APPROVED' | 'PUBLISHED' | 'CANCELLED'
export type CouponStatus = 'ACTIVE' | 'REDEEMED' | 'EXPIRED' | 'CANCELLED'

export interface PromotionResponse {
  id: number
  scopeType: 'TEAM' | 'ORGANIZATION'
  scopeId: number
  title: string
  body: string | null
  imageUrl: string | null
  status: PromotionStatus
  targetSegment: Record<string, unknown> | null
  scheduledAt: string | null
  publishedAt: string | null
  expiresAt: string | null
  recipientCount: number
  openCount: number
  clickCount: number
  createdBy: { id: number; displayName: string } | null
  createdAt: string
}

export interface CouponResponse {
  id: number
  promotionId: number | null
  code: string
  title: string
  description: string | null
  discountType: 'PERCENTAGE' | 'FIXED_AMOUNT'
  discountValue: number
  maxRedemptions: number | null
  currentRedemptions: number
  status: CouponStatus
  validFrom: string
  validUntil: string
  createdAt: string
}

export interface SegmentPreset {
  id: number
  name: string
  conditions: Record<string, unknown>
}
