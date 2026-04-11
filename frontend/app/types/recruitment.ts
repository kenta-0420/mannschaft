/**
 * F03.11 募集型予約の型定義 (Phase 1+5a)。
 * Backend DTO と 1:1 で対応。手動管理 (frontend/app/types/ 規約)。
 */

export type RecruitmentScopeType = 'TEAM' | 'ORGANIZATION'

export type RecruitmentParticipationType = 'INDIVIDUAL' | 'TEAM'

export type RecruitmentVisibility = 'PUBLIC' | 'SCOPE_ONLY' | 'SUPPORTERS_ONLY'

export type RecruitmentListingStatus =
  | 'DRAFT'
  | 'OPEN'
  | 'FULL'
  | 'CLOSED'
  | 'CANCELLED'
  | 'AUTO_CANCELLED'
  | 'COMPLETED'

export type RecruitmentParticipantType = 'USER' | 'TEAM'

export type RecruitmentParticipantStatus =
  | 'APPLIED'
  | 'CONFIRMED'
  | 'WAITLISTED'
  | 'CANCELLED'
  | 'NO_SHOW'
  | 'ATTENDED'

export type CancellationFeeType = 'PERCENTAGE' | 'FIXED'

// ===========================================
// Response (Backend → Frontend)
// ===========================================

export interface RecruitmentCategoryResponse {
  id: number
  code: string
  nameI18nKey: string
  icon: string | null
  defaultParticipationType: RecruitmentParticipationType
  displayOrder: number
  isActive: boolean
}

export interface RecruitmentSubcategoryResponse {
  id: number
  categoryId: number
  scopeType: RecruitmentScopeType
  scopeId: number
  name: string
  displayOrder: number
}

export interface RecruitmentListingResponse {
  id: number
  scopeType: RecruitmentScopeType
  scopeId: number
  categoryId: number
  categoryNameI18nKey: string | null
  subcategoryId: number | null
  subcategoryName: string | null
  title: string
  description: string | null
  participationType: RecruitmentParticipationType
  startAt: string
  endAt: string
  applicationDeadline: string
  autoCancelAt: string
  capacity: number
  minCapacity: number
  confirmedCount: number
  waitlistCount: number
  waitlistMax: number
  paymentEnabled: boolean
  price: number | null
  visibility: RecruitmentVisibility
  status: RecruitmentListingStatus
  location: string | null
  reservationLineId: number | null
  imageUrl: string | null
  cancellationPolicyId: number | null
  createdBy: number
  cancelledAt: string | null
  cancelledBy: number | null
  cancelledReason: string | null
  createdAt: string
  updatedAt: string
}

export interface RecruitmentListingSummaryResponse {
  id: number
  categoryId: number
  categoryNameI18nKey: string | null
  title: string
  participationType: RecruitmentParticipationType
  startAt: string
  endAt: string
  applicationDeadline: string
  capacity: number
  minCapacity: number
  confirmedCount: number
  waitlistCount: number
  status: RecruitmentListingStatus
  visibility: RecruitmentVisibility
  location: string | null
  imageUrl: string | null
  paymentEnabled: boolean
  price: number | null
}

export interface RecruitmentParticipantResponse {
  id: number
  listingId: number
  participantType: RecruitmentParticipantType
  userId: number | null
  teamId: number | null
  appliedBy: number
  status: RecruitmentParticipantStatus
  waitlistPosition: number | null
  note: string | null
  appliedAt: string
  statusChangedAt: string
}

export interface CancellationPolicyTierResponse {
  id: number
  policyId: number
  tierOrder: number
  appliesAtOrBeforeHours: number
  feeType: CancellationFeeType
  feeValue: number
}

export interface CancellationPolicyResponse {
  id: number
  scopeType: RecruitmentScopeType
  scopeId: number
  policyName: string | null
  freeUntilHoursBefore: number
  isTemplatePolicy: boolean
  createdBy: number
  createdAt: string
  updatedAt: string
  tiers: CancellationPolicyTierResponse[]
}

export interface CancellationFeeEstimateResponse {
  listingId: number
  policyId: number | null
  feeAmount: number
  appliedTierId: number | null
  tierOrder: number | null
  feeType: CancellationFeeType | null
  freeUntilApplied: boolean
  hoursBeforeStart: number
  calculatedAt: string
}

// ===========================================
// Request (Frontend → Backend)
// ===========================================

export interface CreateRecruitmentListingRequest {
  categoryId: number
  subcategoryId?: number | null
  title: string
  description?: string | null
  participationType: RecruitmentParticipationType
  startAt: string
  endAt: string
  applicationDeadline: string
  autoCancelAt: string
  capacity: number
  minCapacity: number
  paymentEnabled: boolean
  price?: number | null
  visibility: RecruitmentVisibility
  location?: string | null
  reservationLineId?: number | null
  imageUrl?: string | null
  cancellationPolicyId?: number | null
}

export interface UpdateRecruitmentListingRequest {
  title?: string
  description?: string | null
  subcategoryId?: number | null
  startAt?: string
  endAt?: string
  applicationDeadline?: string
  autoCancelAt?: string
  capacity?: number
  minCapacity?: number
  paymentEnabled?: boolean
  price?: number | null
  visibility?: RecruitmentVisibility
  location?: string | null
  reservationLineId?: number | null
  imageUrl?: string | null
  cancellationPolicyId?: number | null
}

export interface CancelRecruitmentListingRequest {
  reason?: string
}

export interface CreateRecruitmentSubcategoryRequest {
  categoryId: number
  name: string
  displayOrder?: number
}

export interface ApplyToRecruitmentRequest {
  participantType: RecruitmentParticipantType
  teamId?: number | null
  note?: string | null
}

export interface CancelMyApplicationRequest {
  acknowledgedFee: boolean
  feeAmountAtRequest?: number | null
}

export interface CancellationPolicyTierRequest {
  tierOrder: number
  appliesAtOrBeforeHours: number
  feeType: CancellationFeeType
  feeValue: number
}

export interface CreateCancellationPolicyRequest {
  policyName?: string
  freeUntilHoursBefore: number
  isTemplatePolicy: boolean
  tiers?: CancellationPolicyTierRequest[]
}

export interface UpdateCancellationPolicyRequest {
  policyName?: string
  freeUntilHoursBefore?: number
  tiers?: CancellationPolicyTierRequest[]
}
