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
} from '~/types/advertiser'

export function useAdvertiserApi() {
  const api = useApi()

  // ─── 広告主向け API ───

  async function register(organizationId: number, body: RegisterAdvertiserRequest) {
    return api<{ data: AdvertiserAccountResponse }>('/api/v1/advertiser/register', {
      method: 'POST',
      params: { organizationId },
      body,
    })
  }

  async function getAccount(organizationId: number) {
    return api<{ data: AdvertiserAccountResponse }>('/api/v1/advertiser/account', {
      params: { organizationId },
    })
  }

  async function updateAccount(organizationId: number, body: UpdateAdvertiserAccountRequest) {
    return api<{ data: AdvertiserAccountResponse }>('/api/v1/advertiser/account', {
      method: 'PATCH',
      params: { organizationId },
      body,
    })
  }

  async function getOverview(organizationId: number) {
    return api<{ data: AdvertiserOverviewResponse }>('/api/v1/advertiser/overview', {
      params: { organizationId },
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
  async function getCampaignPerformance(campaignId: number, organizationId: number, from: string, to: string) {
    return api<{ data: CampaignPerformanceResponse }>(`/api/v1/advertiser/campaigns/${campaignId}/performance`, {
      params: { organizationId, from, to },
    })
  }

  async function getCreativeComparison(campaignId: number, organizationId: number, from: string, to: string) {
    return api<{ data: CreativeComparisonResponse }>(`/api/v1/advertiser/campaigns/${campaignId}/creatives`, {
      params: { organizationId, from, to },
    })
  }

  async function getBreakdown(campaignId: number, organizationId: number, from: string, to: string, breakdownBy?: string) {
    return api<{ data: BreakdownResponse }>(`/api/v1/advertiser/campaigns/${campaignId}/breakdown`, {
      params: { organizationId, from, to, breakdownBy },
    })
  }

  async function exportCampaignCsv(campaignId: number, organizationId: number, from: string, to: string) {
    return api(`/api/v1/advertiser/campaigns/${campaignId}/export`, {
      params: { organizationId, from, to },
      responseType: 'blob' as const,
    }) as Promise<Blob>
  }

  // Invoices
  async function getInvoices(organizationId: number, params?: { status?: InvoiceStatus; page?: number; size?: number }) {
    return api<{ data: InvoiceSummaryResponse[]; meta: { totalElements: number; page: number; size: number; totalPages: number } }>('/api/v1/advertiser/invoices', {
      params: { organizationId, ...params },
    })
  }

  async function getInvoiceDetail(invoiceId: number, organizationId: number) {
    return api<{ data: InvoiceDetailResponse }>(`/api/v1/advertiser/invoices/${invoiceId}`, {
      params: { organizationId },
    })
  }

  async function downloadInvoicePdf(invoiceId: number, organizationId: number) {
    return api(`/api/v1/advertiser/invoices/${invoiceId}/pdf`, {
      params: { organizationId },
      responseType: 'blob' as const,
    }) as Promise<Blob>
  }

  // Report Schedules
  async function getReportSchedules(organizationId: number) {
    return api<{ data: ReportScheduleResponse[] }>('/api/v1/advertiser/report-schedules', {
      params: { organizationId },
    })
  }

  async function createReportSchedule(organizationId: number, body: CreateReportScheduleRequest) {
    return api<{ data: ReportScheduleResponse }>('/api/v1/advertiser/report-schedules', {
      method: 'POST',
      params: { organizationId },
      body,
    })
  }

  async function deleteReportSchedule(id: number, organizationId: number) {
    return api(`/api/v1/advertiser/report-schedules/${id}`, {
      method: 'DELETE',
      params: { organizationId },
    })
  }

  // Credit Limit Requests
  async function createCreditLimitRequest(organizationId: number, body: CreateCreditLimitRequest) {
    return api<{ data: CreditLimitRequestResponse }>('/api/v1/advertiser/credit-limit-requests', {
      method: 'POST',
      params: { organizationId },
      body,
    })
  }

  async function getCreditLimitRequests(organizationId: number) {
    return api<{ data: CreditLimitRequestResponse[] }>('/api/v1/advertiser/credit-limit-requests', {
      params: { organizationId },
    })
  }

  // ─── SYSTEM_ADMIN 向け API ───

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
