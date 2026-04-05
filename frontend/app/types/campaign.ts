export type CampaignStatus = 'DRAFT' | 'ACTIVE' | 'PAUSED' | 'ENDED' | 'CANCELLED'
export type DiscountType = 'PERCENTAGE' | 'FIXED_AMOUNT'
export type CampaignTarget = 'ALL' | 'MODULE' | 'PACKAGE'

export interface DiscountCampaignResponse {
  id: number
  name: string
  description: string | null
  discountType: DiscountType
  discountValue: number
  target: CampaignTarget
  targetModuleId: string | null
  targetPackageId: string | null
  status: CampaignStatus
  startDate: string
  endDate: string
  createdBy: { id: number; displayName: string } | null
  createdAt: string
  updatedAt: string
}

export interface CreateCampaignRequest {
  name: string
  description?: string
  discountType: DiscountType
  discountValue: number
  target: CampaignTarget
  targetModuleId?: string
  targetPackageId?: string
  startDate: string
  endDate: string
}

export interface UpdateCampaignRequest {
  name?: string
  description?: string
  discountType?: DiscountType
  discountValue?: number
  target?: CampaignTarget
  targetModuleId?: string
  targetPackageId?: string
  startDate?: string
  endDate?: string
  status?: CampaignStatus
}

export interface CampaignCouponResponse {
  id: number
  campaignId: number
  code: string
  maxRedemptions: number | null
  currentRedemptions: number
  isActive: boolean
  createdAt: string
}

export interface CreateCampaignCouponRequest {
  code: string
  maxRedemptions?: number
}

export interface CouponUsageResponse {
  id: number
  couponId: number
  userId: number
  userDisplayName: string
  redeemedAt: string
}
