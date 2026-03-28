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
  timezone: string
  status: string
  hasPassword: boolean
  webauthnCount: number
  oauthProviders: string[]
  lastLoginAt: string | null
  createdAt: string
  is2faEnabled: boolean
}

export interface UpdateProfileRequest {
  lastName?: string
  firstName?: string
  lastNameKana?: string
  firstNameKana?: string
  displayName?: string
  nickname2?: string
  locale?: string
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

export interface CursorMeta {
  nextCursor: string | null
  hasNext: boolean
  limit: number
}

// === Presence ===
export interface PresenceGoingOutRequest {
  destination?: string
  expectedReturnAt?: string
  message?: string
}

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
export interface VehicleResponse {
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

export interface UserViolationHistoryResponse {
  userId: number
  activeWarningCount: number
  activeContentDeleteCount: number
  totalViolationCount: number
  violations: ViolationResponse[]
  yabai: boolean
}

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

export interface PageMeta {
  total: number
  page: number
  size: number
  totalPages: number
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
export interface ResidentResponse {
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
