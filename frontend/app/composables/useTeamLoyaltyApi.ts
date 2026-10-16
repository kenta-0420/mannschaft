import type {
  LoyaltyPage,
  LoyaltyAccountSummary,
  LoyaltyAccount,
  LoyaltyTransaction,
  LoyaltySettings,
  LoyaltyCatalog,
  LoyaltyScanPreview,
  LoyaltyRedemptionPreview,
  LoyaltyBootstrap,
  LoyaltySelfResult,
  LoyaltyAwardRequest,
  LoyaltyStaffAwardBody,
  LoyaltyRedeemBody,
  LoyaltySelfAwardBody,
  LoyaltyRequestAction,
  LoyaltyApproveBody,
  CreateLoyaltyRule,
  CreateLoyaltyReward,
  LoyaltyRule,
  LoyaltyReward,
} from '~/types/team-loyalty'

export function useTeamLoyaltyApi() {
  const api = useApi()
  const team = (id: number) => `/api/v1/teams/${id}/loyalty`
  async function get<T>(url: string) {
    return (await api<{ data: T }>(url)).data
  }
  async function post<T>(url: string, body: object, key?: string) {
    return (
      await api<{ data: T }>(url, {
        method: 'POST',
        body,
        ...(key ? { headers: { 'Idempotency-Key': key } } : {}),
      })
    ).data
  }
  async function put<T>(url: string, body: object) {
    return (await api<{ data: T }>(url, { method: 'PUT', body })).data
  }
  const paging = (cursor?: string | null, status?: string) => {
    const query = new URLSearchParams({ limit: '20' })
    if (cursor) query.set('cursor', cursor)
    if (status) query.set('status', status)
    return `?${query}`
  }
  // CursorPagedResponse は ApiResponse の data 配下ではなくトップレベルに data/meta を持つ。
  return {
    accounts: (cursor?: string | null) =>
      api<LoyaltyPage<LoyaltyAccountSummary>>(`/api/v1/loyalty/accounts/me${paging(cursor)}`),
    account: (id: number) => get<LoyaltyAccount>(`${team(id)}/account/me`),
    transactions: (id: number, cursor?: string | null) =>
      api<LoyaltyPage<LoyaltyTransaction>>(`${team(id)}/transactions/me${paging(cursor)}`),
    catalog: (id: number) => get<LoyaltyCatalog>(`${team(id)}/operation-catalog`),
    settings: (id: number) => get<LoyaltySettings>(`${team(id)}/settings`),
    saveSettings: (
      id: number,
      body: Pick<LoyaltySettings, 'isEnabled' | 'targetAudience' | 'selfAwardMode'> & {
        expectedVersion: number | null
      },
    ) => put<LoyaltySettings>(`${team(id)}/settings`, body),
    createRule: (id: number, body: CreateLoyaltyRule) =>
      post<LoyaltyRule>(`${team(id)}/rules`, body),
    updateRule: (
      id: number,
      ruleId: string,
      body: CreateLoyaltyRule & { expectedVersion: number; isActive: boolean },
    ) => put<LoyaltyRule>(`${team(id)}/rules/${ruleId}`, body),
    createReward: (id: number, body: CreateLoyaltyReward) =>
      post<LoyaltyReward>(`${team(id)}/rewards`, body),
    updateReward: (
      id: number,
      rewardId: string,
      body: CreateLoyaltyReward & { expectedVersion: number; isActive: boolean },
    ) => put<LoyaltyReward>(`${team(id)}/rewards/${rewardId}`, body),
    scan: (id: number, body: { qrToken: string; ruleId: string }) =>
      post<LoyaltyScanPreview>(`${team(id)}/scan-previews`, body),
    redemptionPreview: (id: number, body: { qrToken: string; rewardId: string }) =>
      post<LoyaltyRedemptionPreview>(`${team(id)}/redemption-previews`, body),
    award: (id: number, body: LoyaltyStaffAwardBody, key: string) =>
      post<LoyaltyTransaction>(`${team(id)}/awards`, body, key),
    redeem: (id: number, body: LoyaltyRedeemBody, key: string) =>
      post<LoyaltyTransaction>(`${team(id)}/redemptions`, body, key),
    bootstrap: (locationToken: string) =>
      post<LoyaltyBootstrap>('/api/v1/loyalty/self-award-bootstrap/me', { locationToken }),
    selfAward: (id: number, body: LoyaltySelfAwardBody, key: string) =>
      post<LoyaltySelfResult>(`${team(id)}/self-awards`, body, key),
    myRequests: (cursor?: string | null) =>
      api<LoyaltyPage<LoyaltyAwardRequest>>(`/api/v1/loyalty/award-requests/me${paging(cursor)}`),
    myRequest: (id: string) => get<LoyaltyAwardRequest>(`/api/v1/loyalty/award-requests/${id}/me`),
    cancel: (id: string, body: LoyaltyRequestAction, key: string) =>
      post<LoyaltyAwardRequest>(`/api/v1/loyalty/award-requests/${id}/cancel/me`, body, key),
    requests: (id: number, status: 'PENDING' | 'ALL', cursor?: string | null) =>
      api<LoyaltyPage<LoyaltyAwardRequest>>(`${team(id)}/award-requests${paging(cursor, status)}`),
    request: (id: number, requestId: string) =>
      get<LoyaltyAwardRequest>(`${team(id)}/award-requests/${requestId}`),
    approve: (id: number, requestId: string, body: LoyaltyApproveBody, key: string) =>
      post<LoyaltyTransaction>(`${team(id)}/award-requests/${requestId}/approve`, body, key),
    reject: (id: number, requestId: string, body: LoyaltyRequestAction, key: string) =>
      post<LoyaltyAwardRequest>(`${team(id)}/award-requests/${requestId}/reject`, body, key),
  }
}
