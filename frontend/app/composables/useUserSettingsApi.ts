import type {
  UserProfileResponse,
  UpdateProfileRequest,
  RequestEmailChangeRequest,
  ChangePasswordRequest,
  LoginHistoryResponse,
  PresenceGoingOutRequest,
  PresenceBulkResponse,
  UserVehicleResponse,
  CreateVehicleRequest,
  UpdateVehicleRequest,
  UserViolationHistoryResponse,
  OAuthProviderResponse,
  LinkLineRequest,
  UserLineStatusResponse,
  StripeConnectStatusResponse,
  UserCouponResponse,
  RedeemCouponRequest,
  UserPromotionResponse,
  DwellingUnitResponse,
  UserResidentResponse,
} from '~/types/user-settings'
import type { CursorMeta, PageMeta } from '~/types/api'

interface MessageRes {
  message: string
}

export function useUserSettingsApi() {
  const api = useApi()

  // === Profile ===
  async function getProfile() {
    return api<{ data: UserProfileResponse }>('/api/v1/users/me')
  }

  async function updateProfile(body: UpdateProfileRequest) {
    return api<{ data: UserProfileResponse }>('/api/v1/users/me', {
      method: 'PUT',
      body,
    })
  }

  // === Email ===
  async function changeEmail(body: RequestEmailChangeRequest) {
    return api<{ data: MessageRes }>('/api/v1/users/me/email', {
      method: 'PATCH',
      body,
    })
  }

  async function confirmEmailChange(token: string) {
    return api<{ data: MessageRes }>(
      `/api/v1/users/me/email/confirm?token=${encodeURIComponent(token)}`,
      {
        method: 'POST',
      },
    )
  }

  // === Password ===
  async function changePassword(body: ChangePasswordRequest) {
    return api<{ data: MessageRes }>('/api/v1/users/me/password', {
      method: 'PATCH',
      body,
    })
  }

  async function setupPassword(password: string) {
    return api<{ data: MessageRes }>(
      `/api/v1/users/me/password/setup?password=${encodeURIComponent(password)}`,
      {
        method: 'POST',
      },
    )
  }

  // === Login History ===
  async function getLoginHistory(cursor?: string, limit?: number) {
    const query = new URLSearchParams()
    if (cursor) query.set('cursor', cursor)
    if (limit) query.set('limit', String(limit))
    const qs = query.toString()
    return api<{ data: LoginHistoryResponse[]; meta: CursorMeta }>(
      `/api/v1/users/me/login-history${qs ? `?${qs}` : ''}`,
    )
  }

  // === Presence ===
  async function goOut(body: PresenceGoingOutRequest) {
    return api<{ data: PresenceBulkResponse }>('/api/v1/users/me/presence/going-out', {
      method: 'POST',
      body,
    })
  }

  async function comeHome() {
    return api<{ data: PresenceBulkResponse }>('/api/v1/users/me/presence/home', {
      method: 'POST',
    })
  }

  // === Vehicles ===
  async function getVehicles() {
    return api<{ data: UserVehicleResponse[] }>('/api/v1/users/me/vehicles')
  }

  async function createVehicle(body: CreateVehicleRequest) {
    return api<{ data: UserVehicleResponse }>('/api/v1/users/me/vehicles', {
      method: 'POST',
      body,
    })
  }

  async function updateVehicle(id: number, body: UpdateVehicleRequest) {
    return api<{ data: UserVehicleResponse }>(`/api/v1/users/me/vehicles/${id}`, {
      method: 'PUT',
      body,
    })
  }

  async function deleteVehicle(id: number) {
    return api(`/api/v1/users/me/vehicles/${id}`, { method: 'DELETE' })
  }

  // === Violations ===
  async function getViolations() {
    return api<{ data: UserViolationHistoryResponse }>('/api/v1/users/me/violations')
  }

  // === Withdrawal ===
  async function cancelWithdrawal() {
    return api<{ data: MessageRes }>('/api/v1/users/me/withdrawal/cancel', {
      method: 'POST',
    })
  }

  // === OAuth ===
  async function getOAuthProviders() {
    return api<{ data: OAuthProviderResponse[] }>('/api/v1/users/me/oauth')
  }

  async function unlinkOAuthProvider(provider: string) {
    return api<{ data: MessageRes }>(`/api/v1/users/me/oauth/${encodeURIComponent(provider)}`, {
      method: 'DELETE',
    })
  }

  // === LINE ===
  async function linkLine(body: LinkLineRequest) {
    return api<{ data: UserLineStatusResponse }>('/api/v1/users/me/line/link', {
      method: 'POST',
      body,
    })
  }

  async function unlinkLine() {
    return api('/api/v1/users/me/line/link', { method: 'DELETE' })
  }

  async function getLineStatus() {
    return api<{ data: UserLineStatusResponse }>('/api/v1/users/me/line/status')
  }

  // === Stripe Connect ===
  async function startStripeOnboarding() {
    return api<{ data: Record<string, string> }>('/api/v1/users/me/stripe-connect/onboarding', {
      method: 'POST',
    })
  }

  async function getStripeConnectStatus() {
    return api<{ data: StripeConnectStatusResponse }>('/api/v1/users/me/stripe-connect/status')
  }

  // === Coupons ===
  async function getCoupons() {
    return api<{ data: UserCouponResponse[] }>('/api/v1/users/me/coupons')
  }

  async function redeemCoupon(distributionId: number, body?: RedeemCouponRequest) {
    return api(`/api/v1/users/me/coupons/${distributionId}/redeem`, {
      method: 'POST',
      body: body ?? {},
    })
  }

  // === Promotions ===
  async function getPromotions(page?: number, size?: number) {
    const query = new URLSearchParams()
    if (page !== undefined) query.set('page', String(page))
    if (size !== undefined) query.set('size', String(size))
    const qs = query.toString()
    return api<{ data: UserPromotionResponse[]; meta: PageMeta }>(
      `/api/v1/users/me/promotions${qs ? `?${qs}` : ''}`,
    )
  }

  async function markPromotionAsRead(deliveryId: number) {
    return api(`/api/v1/users/me/promotions/${deliveryId}/read`, {
      method: 'PATCH',
    })
  }

  // === Dwelling Unit ===
  async function getDwellingUnit() {
    return api<{ data: DwellingUnitResponse }>('/api/v1/users/me/dwelling-unit')
  }

  // === Resident Info ===
  async function getResidentInfo() {
    return api<{ data: UserResidentResponse }>('/api/v1/users/me/resident-info')
  }

  return {
    getProfile,
    updateProfile,
    changeEmail,
    confirmEmailChange,
    changePassword,
    setupPassword,
    getLoginHistory,
    goOut,
    comeHome,
    getVehicles,
    createVehicle,
    updateVehicle,
    deleteVehicle,
    getViolations,
    cancelWithdrawal,
    getOAuthProviders,
    unlinkOAuthProvider,
    linkLine,
    unlinkLine,
    getLineStatus,
    startStripeOnboarding,
    getStripeConnectStatus,
    getCoupons,
    redeemCoupon,
    getPromotions,
    markPromotionAsRead,
    getDwellingUnit,
    getResidentInfo,
  }
}
