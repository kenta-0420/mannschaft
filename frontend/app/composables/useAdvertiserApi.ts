import type {
  AdvertiserAccountResponse,
  AdvertiserAccountDetailResponse,
  AdvertiserOverviewResponse,
  RegisterAdvertiserRequest,
  UpdateAdvertiserAccountRequest,
  RateSimulatorResponse,
  PublicRateCardResponse,
  AdRateCardResponse,
  CreateAdRateCardRequest,
  CampaignPerformanceResponse,
  CreativeComparisonResponse,
  BreakdownResponse,
  InvoiceSummaryResponse,
  InvoiceDetailResponse,
  MarkInvoicePaidRequest,
  ReportScheduleResponse,
  CreateReportScheduleRequest,
  CreditLimitRequestResponse,
  CreditLimitRequestDetailResponse,
  CreateCreditLimitRequest,
  RejectCreditLimitRequest,
  SuspendAdvertiserRequest,
  UpdateCreditLimitRequest,
  PricingModel,
  AdvertiserAccountStatus,
  InvoiceStatus,
  CreditLimitRequestStatus,
  AdCreativeResponse,
  CreateAdCreativeRequest,
  UpdateAdCreativeRequest,
  AdCreativeStatus,
  ScopeType,
} from '~/types/advertiser'

// F09.19.6 で billing/analytics/creatives 系メソッドを scope 化（org/team 両対応）。
// 手順は useAdMessagingCampaignApi (F09.17 Phase 11-d-3) に倣うが、BE URL 体系が非対称なため
// 2 系統のヘルパーを用意する:
//
//  1) billing/analytics/account 系（account/overview/invoices/report-schedules/
//     credit-limit-requests/campaigns/{id}/performance,export,creatives(比較),breakdown）:
//     - ORGANIZATION: 旧来の /api/v1/advertiser/{resource} + ?organizationId= クエリ式（変更なし）
//     - TEAM: F09.19.5b で新設された /api/v1/teams/{teamId}/advertiser/{resource} パス式
//     組織側にまだパス式の同等 API が無いため、真に対称な buildBasePath は組めない。
//
//  2) creatives CRUD（ad-campaigns/{campaignId}/creatives 配下）:
//     - 既に org/team 双方がパス式 /api/v1/{organizations|teams}/{scopeId}/advertiser/ad-campaigns/...
//       を持つため、useAdMessagingCampaignApi と同じ buildBasePath パターンをそのまま適用する。

/**
 * billing/analytics/account 系 API の base path を組み立てる。
 *
 * <p>{@code TEAM} は F09.19.5b で新設されたパス式、{@code ORGANIZATION} は
 * 既存のクエリパラメータ式（{@code /api/v1/advertiser/*}）のまま。</p>
 */
function billingBasePath(scopeType: ScopeType, scopeId: string, resource: string): string {
  return scopeType === 'TEAM'
    ? `/api/v1/teams/${scopeId}/advertiser/${resource}`
    : `/api/v1/advertiser/${resource}`
}

/**
 * billing/analytics/account 系 API のクエリパラメータを組み立てる。
 *
 * <p>{@code ORGANIZATION} は {@code organizationId} をクエリに載せる必要があるが、
 * {@code TEAM} は teamId がパスに含まれるため不要。</p>
 */
function billingParams(
  scopeType: ScopeType,
  scopeId: string,
  extra?: Record<string, unknown>,
): Record<string, unknown> | undefined {
  return scopeType === 'TEAM' ? extra : { organizationId: scopeId, ...extra }
}

function scopeSegment(scopeType: ScopeType): 'organizations' | 'teams' {
  return scopeType === 'TEAM' ? 'teams' : 'organizations'
}

/** creatives CRUD（ad-campaigns/{campaignId}/creatives）の base path を組み立てる。 */
function creativesBasePath(scopeType: ScopeType, scopeId: string, campaignId: number): string {
  return `/api/v1/${scopeSegment(scopeType)}/${scopeId}/advertiser/ad-campaigns/${campaignId}/creatives`
}

export function useAdvertiserApi() {
  const api = useApi()

  // ─── 広告主向け API ───

  async function register(organizationId: string, body: RegisterAdvertiserRequest) {
    return api<{ data: AdvertiserAccountResponse }>('/api/v1/advertiser/register', {
      method: 'POST',
      params: { organizationId },
      body,
    })
  }

  async function registerTeam(teamId: string, body: RegisterAdvertiserRequest) {
    return api<{ data: AdvertiserAccountResponse }>(`/api/v1/teams/${teamId}/advertiser/register`, {
      method: 'POST',
      body,
    })
  }

  async function getAccount(scopeType: ScopeType, scopeId: string) {
    return api<{ data: AdvertiserAccountResponse }>(billingBasePath(scopeType, scopeId, 'account'), {
      params: billingParams(scopeType, scopeId),
    })
  }

  async function updateAccount(scopeType: ScopeType, scopeId: string, body: UpdateAdvertiserAccountRequest) {
    return api<{ data: AdvertiserAccountResponse }>(billingBasePath(scopeType, scopeId, 'account'), {
      method: 'PATCH',
      params: billingParams(scopeType, scopeId),
      body,
    })
  }

  async function getOverview(scopeType: ScopeType, scopeId: string) {
    return api<{ data: AdvertiserOverviewResponse }>(billingBasePath(scopeType, scopeId, 'overview'), {
      params: billingParams(scopeType, scopeId),
    })
  }

  async function simulateRate(params: {
    prefecture?: string
    template?: string
    pricingModel: PricingModel
    impressions?: number
    clicks?: number
    days?: number
  }) {
    return api<{ data: RateSimulatorResponse }>('/api/v1/advertiser/rate-simulator', {
      params,
    })
  }

  async function getRateCards(params?: { pricingModel?: PricingModel; prefecture?: string }) {
    return api<{ data: PublicRateCardResponse[] }>('/api/v1/advertiser/rate-cards', {
      params,
    })
  }

  // Performance
  async function getCampaignPerformance(scopeType: ScopeType, scopeId: string, campaignId: number, from: string, to: string) {
    return api<{ data: CampaignPerformanceResponse }>(billingBasePath(scopeType, scopeId, `campaigns/${campaignId}/performance`), {
      params: billingParams(scopeType, scopeId, { from, to }),
    })
  }

  async function getCreativeComparison(scopeType: ScopeType, scopeId: string, campaignId: number, from: string, to: string) {
    return api<{ data: CreativeComparisonResponse }>(billingBasePath(scopeType, scopeId, `campaigns/${campaignId}/creatives`), {
      params: billingParams(scopeType, scopeId, { from, to }),
    })
  }

  async function getBreakdown(scopeType: ScopeType, scopeId: string, campaignId: number, from: string, to: string, breakdownBy?: string) {
    return api<{ data: BreakdownResponse }>(billingBasePath(scopeType, scopeId, `campaigns/${campaignId}/breakdown`), {
      params: billingParams(scopeType, scopeId, { from, to, breakdownBy }),
    })
  }

  async function exportCampaignCsv(scopeType: ScopeType, scopeId: string, campaignId: number, from: string, to: string) {
    return api(billingBasePath(scopeType, scopeId, `campaigns/${campaignId}/export`), {
      params: billingParams(scopeType, scopeId, { from, to }),
      responseType: 'blob' as const,
    }) as Promise<Blob>
  }

  // Invoices
  async function getInvoices(scopeType: ScopeType, scopeId: string, params?: { status?: InvoiceStatus; page?: number; size?: number }) {
    return api<{ data: InvoiceSummaryResponse[]; meta: { totalElements: number; page: number; size: number; totalPages: number } }>(billingBasePath(scopeType, scopeId, 'invoices'), {
      params: billingParams(scopeType, scopeId, params),
    })
  }

  async function getInvoiceDetail(scopeType: ScopeType, scopeId: string, invoiceId: number) {
    return api<{ data: InvoiceDetailResponse }>(billingBasePath(scopeType, scopeId, `invoices/${invoiceId}`), {
      params: billingParams(scopeType, scopeId),
    })
  }

  async function downloadInvoicePdf(scopeType: ScopeType, scopeId: string, invoiceId: number) {
    return api(billingBasePath(scopeType, scopeId, `invoices/${invoiceId}/pdf`), {
      params: billingParams(scopeType, scopeId),
      responseType: 'blob' as const,
    }) as Promise<Blob>
  }

  // Report Schedules
  async function getReportSchedules(scopeType: ScopeType, scopeId: string) {
    return api<{ data: ReportScheduleResponse[] }>(billingBasePath(scopeType, scopeId, 'report-schedules'), {
      params: billingParams(scopeType, scopeId),
    })
  }

  async function createReportSchedule(scopeType: ScopeType, scopeId: string, body: CreateReportScheduleRequest) {
    return api<{ data: ReportScheduleResponse }>(billingBasePath(scopeType, scopeId, 'report-schedules'), {
      method: 'POST',
      params: billingParams(scopeType, scopeId),
      body,
    })
  }

  async function deleteReportSchedule(scopeType: ScopeType, scopeId: string, id: number) {
    return api(billingBasePath(scopeType, scopeId, `report-schedules/${id}`), {
      method: 'DELETE',
      params: billingParams(scopeType, scopeId),
    })
  }

  // Credit Limit Requests
  async function createCreditLimitRequest(scopeType: ScopeType, scopeId: string, body: CreateCreditLimitRequest) {
    return api<{ data: CreditLimitRequestResponse }>(billingBasePath(scopeType, scopeId, 'credit-limit-requests'), {
      method: 'POST',
      params: billingParams(scopeType, scopeId),
      body,
    })
  }

  async function getCreditLimitRequests(scopeType: ScopeType, scopeId: string) {
    return api<{ data: CreditLimitRequestResponse[] }>(billingBasePath(scopeType, scopeId, 'credit-limit-requests'), {
      params: billingParams(scopeType, scopeId),
    })
  }

  // ─── 広告主向け クリエイティブ API ───

  async function createCreative(scopeType: ScopeType, scopeId: string, campaignId: number, body: CreateAdCreativeRequest) {
    return api<{ data: AdCreativeResponse }>(
      creativesBasePath(scopeType, scopeId, campaignId),
      { method: 'POST', body },
    )
  }

  async function listCreatives(scopeType: ScopeType, scopeId: string, campaignId: number) {
    return api<{ data: AdCreativeResponse[] }>(
      creativesBasePath(scopeType, scopeId, campaignId),
    )
  }

  async function updateCreative(scopeType: ScopeType, scopeId: string, campaignId: number, adId: number, body: UpdateAdCreativeRequest) {
    return api<{ data: AdCreativeResponse }>(
      `${creativesBasePath(scopeType, scopeId, campaignId)}/${adId}`,
      { method: 'PUT', body },
    )
  }

  async function deleteCreative(scopeType: ScopeType, scopeId: string, campaignId: number, adId: number) {
    return api(
      `${creativesBasePath(scopeType, scopeId, campaignId)}/${adId}`,
      { method: 'DELETE' },
    )
  }

  // ─── SYSTEM_ADMIN 向け API ───

  async function adminListCreatives(params?: { status?: AdCreativeStatus }) {
    return api<{ data: AdCreativeResponse[] }>('/api/v1/system-admin/ad-creatives', { params })
  }

  async function adminApproveCreative(adId: number) {
    return api<{ data: AdCreativeResponse }>(`/api/v1/system-admin/ad-creatives/${adId}/approve`, {
      method: 'PATCH',
    })
  }

  async function adminRejectCreative(adId: number) {
    return api<{ data: AdCreativeResponse }>(`/api/v1/system-admin/ad-creatives/${adId}/reject`, {
      method: 'PATCH',
    })
  }

  async function adminGetRateCards(params?: { pricingModel?: PricingModel; prefecture?: string; activeOnly?: boolean; page?: number; size?: number }) {
    return api<{ data: AdRateCardResponse[]; meta: { totalElements: number; page: number; size: number; totalPages: number } }>('/api/v1/system-admin/ad-rate-cards', {
      params,
    })
  }

  async function adminCreateRateCard(body: CreateAdRateCardRequest) {
    return api<{ data: AdRateCardResponse }>('/api/v1/system-admin/ad-rate-cards', {
      method: 'POST',
      body,
    })
  }

  async function adminDeleteRateCard(id: number) {
    return api(`/api/v1/system-admin/ad-rate-cards/${id}`, {
      method: 'DELETE',
    })
  }

  async function adminGetAdvertiserAccounts(params?: { status?: AdvertiserAccountStatus; page?: number; size?: number }) {
    return api<{ data: AdvertiserAccountDetailResponse[]; meta: { totalElements: number; page: number; size: number; totalPages: number } }>('/api/v1/system-admin/advertiser-accounts', {
      params,
    })
  }

  async function adminApproveAccount(id: number) {
    return api<{ data: AdvertiserAccountResponse }>(`/api/v1/system-admin/advertiser-accounts/${id}/approve`, {
      method: 'PATCH',
    })
  }

  async function adminSuspendAccount(id: number, body: SuspendAdvertiserRequest) {
    return api<{ data: AdvertiserAccountResponse }>(`/api/v1/system-admin/advertiser-accounts/${id}/suspend`, {
      method: 'PATCH',
      body,
    })
  }

  async function adminUpdateCreditLimit(id: number, body: UpdateCreditLimitRequest) {
    return api<{ data: AdvertiserAccountResponse }>(`/api/v1/system-admin/advertiser-accounts/${id}/credit-limit`, {
      method: 'PATCH',
      body,
    })
  }

  async function adminMarkInvoicePaid(id: number, body: MarkInvoicePaidRequest) {
    return api<{ data: InvoiceSummaryResponse }>(`/api/v1/system-admin/ad-invoices/${id}/mark-paid`, {
      method: 'PATCH',
      body,
    })
  }

  async function adminGetCreditLimitRequests(params?: { status?: CreditLimitRequestStatus; page?: number; size?: number }) {
    return api<{ data: CreditLimitRequestDetailResponse[]; meta: { totalElements: number; page: number; size: number; totalPages: number } }>('/api/v1/system-admin/ad-credit-limit-requests', {
      params,
    })
  }

  async function adminApproveCreditLimitRequest(id: number) {
    return api<{ data: CreditLimitRequestResponse }>(`/api/v1/system-admin/ad-credit-limit-requests/${id}/approve`, {
      method: 'PATCH',
    })
  }

  async function adminRejectCreditLimitRequest(id: number, body: RejectCreditLimitRequest) {
    return api<{ data: CreditLimitRequestResponse }>(`/api/v1/system-admin/ad-credit-limit-requests/${id}/reject`, {
      method: 'PATCH',
      body,
    })
  }

  return {
    // Advertiser
    register,
    registerTeam,
    getAccount,
    updateAccount,
    getOverview,
    simulateRate,
    getRateCards,
    getCampaignPerformance,
    getCreativeComparison,
    getBreakdown,
    exportCampaignCsv,
    getInvoices,
    getInvoiceDetail,
    downloadInvoicePdf,
    getReportSchedules,
    createReportSchedule,
    deleteReportSchedule,
    createCreditLimitRequest,
    getCreditLimitRequests,
    // Creatives（広告主向け）
    createCreative,
    listCreatives,
    updateCreative,
    deleteCreative,
    // Creatives（SYSTEM_ADMIN向け）
    adminListCreatives,
    adminApproveCreative,
    adminRejectCreative,
    // Admin
    adminGetRateCards,
    adminCreateRateCard,
    adminDeleteRateCard,
    adminGetAdvertiserAccounts,
    adminApproveAccount,
    adminSuspendAccount,
    adminUpdateCreditLimit,
    adminMarkInvoicePaid,
    adminGetCreditLimitRequests,
    adminApproveCreditLimitRequest,
    adminRejectCreditLimitRequest,
  }
}
