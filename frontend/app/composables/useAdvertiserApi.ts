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

interface PageMeta {
  totalElements: number
  page: number
  size: number
  totalPages: number
}

interface PagedEnvelope<T> {
  data: T[]
  meta: PageMeta
}

export function useAdvertiserApi() {
  const api = useApi()

  // ─── 広告主向け API（org/team 両対応。F09.19.6 で scope 化） ───

  /**
   * 広告主アカウントを新規登録する。
   *
   * org は旧 `/api/v1/advertiser/register?organizationId=` （F09.19.5 でも scope 化されず残置）、
   * team は `/api/v1/teams/{teamId}/advertiser/register`（F09.17 Phase 11-d-4 で新設済み）と
   * URL 体系が異なるため、内部で分岐する。
   */
  async function register(scopeType: ScopeType, scopeId: string, body: RegisterAdvertiserRequest) {
    if (scopeType === 'TEAM') {
      return api<{ data: AdvertiserAccountResponse }>(`/api/v1/teams/${scopeId}/advertiser/register`, {
        method: 'POST',
        body,
      })
    }
    return api<{ data: AdvertiserAccountResponse }>('/api/v1/advertiser/register', {
      method: 'POST',
      params: { organizationId: scopeId },
      body,
    })
  }

  /**
   * 広告主アカウント取得（ORGANIZATION scope のみ）。
   *
   * F09.19.5 では scope 化されず、`/api/v1/advertiser/account` が
   * `@RequestParam Long organizationId` 固定のまま残置されている
   * （{@code AdvertiserDashboardController}）。TEAM scope 用のアカウント参照 API は
   * バックエンド未実装のため、team ページからは呼び出さないこと（F09.19.6 では未対応）。
   */
  async function getAccount(organizationId: string) {
    return api<{ data: AdvertiserAccountResponse }>('/api/v1/advertiser/account', {
      params: { organizationId },
    })
  }

  /** 広告主アカウント更新（ORGANIZATION scope のみ。getAccount と同じ理由でTEAM未対応）。 */
  async function updateAccount(organizationId: string, body: UpdateAdvertiserAccountRequest) {
    return api<{ data: AdvertiserAccountResponse }>('/api/v1/advertiser/account', {
      method: 'PATCH',
      params: { organizationId },
      body,
    })
  }

  /** ダッシュボード概況（ORGANIZATION scope のみ。TEAM未対応・BE未実装）。 */
  async function getOverview(organizationId: string) {
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

  // ─── Performance（org/team 両対応。F09.19.5 で team scope の performance のみ新設済み） ───

  /**
   * キャンペーン別パフォーマンス。org は旧 `/api/v1/advertiser/campaigns/{id}/performance`、
   * team は `/api/v1/teams/{teamId}/advertiser/campaigns/{id}/performance`
   * （{@link TeamAdvertiserDashboardController}。F09.19.5 AC-5.2）に振り分ける。
   */
  async function getCampaignPerformance(scopeType: ScopeType, scopeId: string, campaignId: number, from: string, to: string) {
    if (scopeType === 'TEAM') {
      return api<{ data: CampaignPerformanceResponse }>(
        `/api/v1/teams/${scopeId}/advertiser/campaigns/${campaignId}/performance`,
        { params: { from, to } },
      )
    }
    return api<{ data: CampaignPerformanceResponse }>(`/api/v1/advertiser/campaigns/${campaignId}/performance`, {
      params: { organizationId: scopeId, from, to },
    })
  }

  /**
   * クリエイティブ比較（ORGANIZATION scope のみ）。
   *
   * F09.19.5 で team 向けの `/api/v1/teams/{teamId}/advertiser/campaigns/{id}/creatives`
   * は新設されなかった（{@code CampaignPerformanceService.getCreativeComparison} に
   * scope 引数オーバーロードが存在しない）。TEAM scope からは呼び出さないこと（F09.19.6 では未対応）。
   */
  async function getCreativeComparison(campaignId: number, organizationId: string, from: string, to: string) {
    return api<{ data: CreativeComparisonResponse }>(`/api/v1/advertiser/campaigns/${campaignId}/creatives`, {
      params: { organizationId, from, to },
    })
  }

  /** ブレイクダウン（ORGANIZATION scope のみ。getCreativeComparison と同じ理由でTEAM未対応）。 */
  async function getBreakdown(campaignId: number, organizationId: string, from: string, to: string, breakdownBy?: string) {
    return api<{ data: BreakdownResponse }>(`/api/v1/advertiser/campaigns/${campaignId}/breakdown`, {
      params: { organizationId, from, to, breakdownBy },
    })
  }

  /** CSV エクスポート（ORGANIZATION scope のみ。TEAM未対応・BE未実装）。 */
  async function exportCampaignCsv(campaignId: number, organizationId: string, from: string, to: string) {
    return api(`/api/v1/advertiser/campaigns/${campaignId}/export`, {
      params: { organizationId, from, to },
      responseType: 'blob' as const,
    }) as Promise<Blob>
  }

  // ─── Invoices（一覧のみ org/team 両対応。詳細・PDF は ORGANIZATION scope のみ） ───

  /**
   * 請求書一覧。org は旧 `/api/v1/advertiser/invoices`、team は
   * `/api/v1/teams/{teamId}/advertiser/invoices`（PagedResponse。F09.19.5 AC-5.2）に振り分ける。
   * 応答形式はいずれも `{ data: InvoiceSummaryResponse[]; meta: {...} }` で同一。
   */
  async function getInvoices(scopeType: ScopeType, scopeId: string, params?: { status?: InvoiceStatus; page?: number; size?: number }) {
    if (scopeType === 'TEAM') {
      return api<PagedEnvelope<InvoiceSummaryResponse>>(`/api/v1/teams/${scopeId}/advertiser/invoices`, {
        params,
      })
    }
    return api<PagedEnvelope<InvoiceSummaryResponse>>('/api/v1/advertiser/invoices', {
      params: { organizationId: scopeId, ...params },
    })
  }

  /**
   * 請求書詳細（ORGANIZATION scope のみ）。
   *
   * team 向けの `GET /api/v1/teams/{teamId}/advertiser/invoices/{id}` は F09.19.5 で
   * 新設されなかった（{@link TeamAdvertiserDashboardController} は一覧のみ）。
   * サービス層の {@code AdInvoiceService.getDetail(invoiceId, advertiserAccountId)} 自体は
   * scope 非依存だが、コントローラー未実装のため TEAM scope からは呼び出さないこと（F09.19.6 では未対応）。
   */
  async function getInvoiceDetail(invoiceId: number, organizationId: string) {
    return api<{ data: InvoiceDetailResponse }>(`/api/v1/advertiser/invoices/${invoiceId}`, {
      params: { organizationId },
    })
  }

  /** 請求書 PDF ダウンロード（ORGANIZATION scope のみ。getInvoiceDetail と同じ理由でTEAM未対応）。 */
  async function downloadInvoicePdf(invoiceId: number, organizationId: string) {
    return api(`/api/v1/advertiser/invoices/${invoiceId}/pdf`, {
      params: { organizationId },
      responseType: 'blob' as const,
    }) as Promise<Blob>
  }

  // ─── Report Schedules（一覧のみ org/team 両対応。作成・削除は ORGANIZATION scope のみ） ───

  /**
   * 定期レポートスケジュール一覧。org は旧 `/api/v1/advertiser/report-schedules`、team は
   * `/api/v1/teams/{teamId}/advertiser/report-schedules`（F09.19.5 AC-5.2）に振り分ける。
   */
  async function getReportSchedules(scopeType: ScopeType, scopeId: string) {
    if (scopeType === 'TEAM') {
      return api<{ data: ReportScheduleResponse[] }>(`/api/v1/teams/${scopeId}/advertiser/report-schedules`)
    }
    return api<{ data: ReportScheduleResponse[] }>('/api/v1/advertiser/report-schedules', {
      params: { organizationId: scopeId },
    })
  }

  /**
   * レポートスケジュール作成（ORGANIZATION scope のみ）。
   *
   * {@code AdReportScheduleService.create(organizationId, ...)} が ScopeType.ORGANIZATION に
   * 固定されたままで、team 向けの POST エンドポイントも未実装。TEAM scope からは呼び出さないこと
   * （F09.19.6 では未対応）。
   */
  async function createReportSchedule(organizationId: string, body: CreateReportScheduleRequest) {
    return api<{ data: ReportScheduleResponse }>('/api/v1/advertiser/report-schedules', {
      method: 'POST',
      params: { organizationId },
      body,
    })
  }

  /** レポートスケジュール削除（ORGANIZATION scope のみ。createReportSchedule と同じ理由でTEAM未対応）。 */
  async function deleteReportSchedule(id: number, organizationId: string) {
    return api(`/api/v1/advertiser/report-schedules/${id}`, {
      method: 'DELETE',
      params: { organizationId },
    })
  }

  // ─── Credit Limit Requests（一覧のみ org/team 両対応。作成は ORGANIZATION scope のみ） ───

  /**
   * 与信枠増額申請（ORGANIZATION scope のみ）。
   *
   * {@code AdCreditLimitRequestService.create(organizationId, ...)} が ScopeType.ORGANIZATION に
   * 固定されたままで、team 向けの POST エンドポイントも未実装。TEAM scope からは呼び出さないこと
   * （F09.19.6 では未対応）。
   */
  async function createCreditLimitRequest(organizationId: string, body: CreateCreditLimitRequest) {
    return api<{ data: CreditLimitRequestResponse }>('/api/v1/advertiser/credit-limit-requests', {
      method: 'POST',
      params: { organizationId },
      body,
    })
  }

  /**
   * 与信枠増額申請の履歴一覧。org は旧 `/api/v1/advertiser/credit-limit-requests`、team は
   * `/api/v1/teams/{teamId}/advertiser/credit-limit-requests`（F09.19.5 AC-5.2）に振り分ける。
   */
  async function getCreditLimitRequests(scopeType: ScopeType, scopeId: string) {
    if (scopeType === 'TEAM') {
      return api<{ data: CreditLimitRequestResponse[] }>(`/api/v1/teams/${scopeId}/advertiser/credit-limit-requests`)
    }
    return api<{ data: CreditLimitRequestResponse[] }>('/api/v1/advertiser/credit-limit-requests', {
      params: { organizationId: scopeId },
    })
  }

  // ─── 広告主向け クリエイティブ API（ORGANIZATION scope のみ） ───
  //
  // {@link AdvertiserAdCreativeController} は
  // `/api/v1/organizations/{organizationId}/advertiser/ad-campaigns/{campaignId}/creatives`
  // に固定されており、team 向けの対称エンドポイントは F09.19.5 でも新設されなかった。
  // TEAM scope からは呼び出さないこと（F09.19.6 では未対応。creatives/index.vue の team 版は未実装）。

  async function createCreative(organizationId: string, campaignId: number, body: CreateAdCreativeRequest) {
    return api<{ data: AdCreativeResponse }>(
      `/api/v1/organizations/${organizationId}/advertiser/ad-campaigns/${campaignId}/creatives`,
      { method: 'POST', body },
    )
  }

  async function listCreatives(organizationId: string, campaignId: number) {
    return api<{ data: AdCreativeResponse[] }>(
      `/api/v1/organizations/${organizationId}/advertiser/ad-campaigns/${campaignId}/creatives`,
    )
  }

  async function updateCreative(organizationId: string, campaignId: number, adId: number, body: UpdateAdCreativeRequest) {
    return api<{ data: AdCreativeResponse }>(
      `/api/v1/organizations/${organizationId}/advertiser/ad-campaigns/${campaignId}/creatives/${adId}`,
      { method: 'PUT', body },
    )
  }

  async function deleteCreative(organizationId: string, campaignId: number, adId: number) {
    return api(
      `/api/v1/organizations/${organizationId}/advertiser/ad-campaigns/${campaignId}/creatives/${adId}`,
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
