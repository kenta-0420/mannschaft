// === Profile ===
export interface UserProfileResponse {
  id: number
  email: string
  lastName: string
  firstName: string
  lastNameKana: string
  firstNameKana: string
  displayName: string
  nickname2: string
  isSearchable: boolean
  avatarUrl: string | null
  phoneNumber: string
  locale: string
  /** ISO 3166-1 alpha-2 国コード（例: JP・US・DE）。未設定時は null。 */
  countryCode: string | null
  timezone: string
  status: string
  hasPassword: boolean
  webauthnCount: number
  oauthProviders: string[]
  lastLoginAt: string | null
  createdAt: string
  is2faEnabled: boolean
  contactHandle: string | null
  handleSearchable: boolean
  contactApprovalRequired: boolean
  dmReceiveFrom: 'ANYONE' | 'TEAM_MEMBERS_ONLY' | 'CONTACTS_ONLY'
  onlineVisibility: 'NOBODY' | 'CONTACTS_ONLY' | 'EVERYONE'
}

export interface UpdateProfileRequest {
  lastName?: string
  firstName?: string
  lastNameKana?: string
  firstNameKana?: string
  displayName?: string
  nickname2?: string
  locale?: string
  /** ISO 3166-1 alpha-2 国コード（例: JP・US・DE）。null 送信時はクリア。 */
  countryCode?: string | null
  timezone?: string
  isSearchable?: boolean
  avatarUrl?: string
  phoneNumber?: string
  postalCode?: string
}

// === Email ===
export interface RequestEmailChangeRequest {
  newEmail: string
  currentPassword: string
}

// === Password ===
export interface ChangePasswordRequest {
  currentPassword: string
  newPassword: string
}

// === Login History ===
export interface LoginHistoryResponse {
  id: number
  eventType: string
  ipAddress: string
  userAgent: string
  method: string
  createdAt: string
}

// === Presence ===
// PresenceGoingOutRequest は presence.ts で定義（重複を避けるため再エクスポート）
export type { PresenceGoingOutRequest } from './presence'

export interface NotifiedTeam {
  teamId: number
  teamName: string
  eventId: number
}

export interface SkippedTeam {
  teamId: number
  teamName: string
}

export interface PresenceBulkResponse {
  notifiedTeams: NotifiedTeam[]
  skippedTeams: SkippedTeam[]
}

// === Vehicles ===
// parking.ts の VehicleResponse とは異なる構造（ユーザー自身の車両管理 API 用）
export interface UserVehicleResponse {
  id: number
  userId: number
  vehicleType: string
  plateNumber: string
  nickname: string
  createdAt: string
  updatedAt: string
}

export interface CreateVehicleRequest {
  vehicleType: string
  plateNumber?: string
  nickname?: string
}

export interface UpdateVehicleRequest {
  vehicleType: string
  plateNumber?: string
  nickname?: string
}

// === Violations ===
// ViolationResponse と UserViolationHistoryResponse は admin-report.ts で定義（重複を避けるため再エクスポート）
export type { ViolationResponse, UserViolationHistoryResponse } from './admin-report'

// === OAuth ===
export interface OAuthProviderResponse {
  provider: string
  providerEmail: string
  connectedAt: string
}

// === LINE ===
export interface LinkLineRequest {
  lineUserId?: string
  displayName?: string
  pictureUrl?: string
  statusMessage?: string
}

export interface UserLineStatusResponse {
  isLinked: boolean
  lineUserId: string | null
  displayName: string | null
  pictureUrl: string | null
  statusMessage: string | null
  isActive: boolean
  linkedAt: string | null
}

// === Stripe Connect ===
export interface StripeConnectStatusResponse {
  userId: number
  stripeAccountId: string | null
  chargesEnabled: boolean
  payoutsEnabled: boolean
  onboardingCompleted: boolean
}

// === Coupons ===
export interface UserCouponResponse {
  distributionId: number
  couponId: number
  title: string
  description: string
  couponType: string
  discountValue: number
  status: string
  distributedAt: string
  expiresAt: string | null
}

export interface RedeemCouponRequest {
  redemptionDetail?: string
}

// === Promotions ===
export interface UserPromotionResponse {
  deliveryId: number
  promotionId: number
  title: string
  body: string
  imageUrl: string | null
  channel: string
  status: string
  deliveredAt: string
  openedAt: string | null
  createdAt: string
}

// === Dwelling Unit ===
export interface DwellingUnitResponse {
  id: number
  scopeType: string
  teamId: number | null
  organizationId: number | null
  unitNumber: string
  floor: number
  areaSqm: number
  layout: string
  unitType: string
  notes: string | null
  residentCount: number
  createdAt: string
  updatedAt: string
}

// === Resident Info ===
// resident.ts の ResidentResponse とは異なる構造（ユーザー自身の居住者情報 API 用）
export interface UserResidentResponse {
  id: number
  dwellingUnitId: number
  userId: number
  residentType: string
  lastName: string
  firstName: string
  lastNameKana: string
  firstNameKana: string
  phone: string
  email: string
  emergencyContact: string | null
  moveInDate: string | null
  moveOutDate: string | null
  ownershipRatio: number | null
  isPrimary: boolean
  isVerified: boolean
  verifiedBy: number | null
  verifiedAt: string | null
  notes: string | null
  createdAt: string
}

// === Withdrawal ===
export interface RequestWithdrawalRequest {
  currentPassword?: string
}
