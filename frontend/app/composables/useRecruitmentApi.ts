import type {
  ApplyToRecruitmentRequest,
  CancelMyApplicationRequest,
  CancelRecruitmentListingRequest,
  CancellationFeeEstimateResponse,
  CancellationPolicyResponse,
  CreateCancellationPolicyRequest,
  CreateRecruitmentListingRequest,
  CreateRecruitmentSubcategoryRequest,
  DisputeNoShowRequest,
  LiftPenaltyRequest,
  RecruitmentCategoryResponse,
  RecruitmentListingResponse,
  RecruitmentListingSummaryResponse,
  RecruitmentNoShowRecordResponse,
  RecruitmentParticipantResponse,
  RecruitmentPenaltySettingResponse,
  RecruitmentSearchParams,
  RecruitmentSubcategoryResponse,
  RecruitmentUserPenaltyResponse,
  ResolveDisputeRequest,
  UpdateCancellationPolicyRequest,
  UpdateRecruitmentListingRequest,
  UpsertPenaltySettingRequest,
} from '~/types/recruitment'

interface ApiResponse<T> {
  data: T
}

interface PagedResponse<T> {
  data: T[]
  meta: {
    totalElements: number
    pageNumber: number
    pageSize: number
    totalPages: number
  }
}

/**
 * F03.11 募集型予約 API クライアント (Phase 1+5a)。
 */
export function useRecruitmentApi() {
  const api = useApi()

  // ===========================================
  // カテゴリ (§9.7)
  // ===========================================

  async function listCategories() {
    return api<ApiResponse<RecruitmentCategoryResponse[]>>('/api/v1/recruitment-categories')
  }

  // ===========================================
  // サブカテゴリ (§9.6)
  // ===========================================

  async function listTeamSubcategories(teamId: number, categoryId?: number) {
    const q = new URLSearchParams()
    if (categoryId != null) q.set('categoryId', String(categoryId))
    const suffix = q.toString() ? `?${q.toString()}` : ''
    return api<ApiResponse<RecruitmentSubcategoryResponse[]>>(
      `/api/v1/teams/${teamId}/recruitment-subcategories${suffix}`,
    )
  }

  async function createTeamSubcategory(teamId: number, body: CreateRecruitmentSubcategoryRequest) {
    return api<ApiResponse<RecruitmentSubcategoryResponse>>(
      `/api/v1/teams/${teamId}/recruitment-subcategories`,
      { method: 'POST', body },
    )
  }

  async function archiveTeamSubcategory(teamId: number, subcategoryId: number) {
    return api(
      `/api/v1/teams/${teamId}/recruitment-subcategories/${subcategoryId}/archive`,
      { method: 'POST' },
    )
  }

  // ===========================================
  // 募集枠 CRUD (§9.1)
  // ===========================================

  async function listTeamListings(
    teamId: number,
    params?: { status?: string; page?: number; size?: number },
  ) {
    const q = new URLSearchParams()
    if (params?.status) q.set('status', params.status)
    if (params?.page != null) q.set('page', String(params.page))
    if (params?.size != null) q.set('size', String(params.size))
    const suffix = q.toString() ? `?${q.toString()}` : ''
    return api<PagedResponse<RecruitmentListingSummaryResponse>>(
      `/api/v1/teams/${teamId}/recruitment-listings${suffix}`,
    )
  }

  async function createListing(teamId: number, body: CreateRecruitmentListingRequest) {
    return api<ApiResponse<RecruitmentListingResponse>>(
      `/api/v1/teams/${teamId}/recruitment-listings`,
      { method: 'POST', body },
    )
  }

  async function getListing(listingId: number) {
    return api<ApiResponse<RecruitmentListingResponse>>(
      `/api/v1/recruitment-listings/${listingId}`,
    )
  }

  async function updateListing(listingId: number, body: UpdateRecruitmentListingRequest) {
    return api<ApiResponse<RecruitmentListingResponse>>(
      `/api/v1/recruitment-listings/${listingId}`,
      { method: 'PATCH', body },
    )
  }

  async function publishListing(listingId: number) {
    return api<ApiResponse<RecruitmentListingResponse>>(
      `/api/v1/recruitment-listings/${listingId}/publish`,
      { method: 'POST' },
    )
  }

  async function cancelListing(listingId: number, body?: CancelRecruitmentListingRequest) {
    return api<ApiResponse<RecruitmentListingResponse>>(
      `/api/v1/recruitment-listings/${listingId}/cancel`,
      { method: 'POST', body: body ?? {} },
    )
  }

  async function archiveListing(listingId: number) {
    return api(`/api/v1/recruitment-listings/${listingId}/archive`, { method: 'POST' })
  }

  async function estimateCancellationFee(listingId: number, at?: string) {
    const q = at ? `?at=${encodeURIComponent(at)}` : ''
    return api<ApiResponse<CancellationFeeEstimateResponse>>(
      `/api/v1/recruitment-listings/${listingId}/cancellation-fee-estimate${q}`,
    )
  }

  // ===========================================
  // 参加申込 (§9.2, §9.10)
  // ===========================================

  async function applyToListing(listingId: number, body: ApplyToRecruitmentRequest) {
    return api<ApiResponse<RecruitmentParticipantResponse>>(
      `/api/v1/recruitment-listings/${listingId}/applications`,
      { method: 'POST', body },
    )
  }

  async function cancelMyApplication(listingId: number, body: CancelMyApplicationRequest) {
    return api<ApiResponse<RecruitmentParticipantResponse>>(
      `/api/v1/recruitment-listings/${listingId}/applications/me`,
      { method: 'DELETE', body },
    )
  }

  async function listListingParticipants(
    listingId: number,
    params?: { page?: number; size?: number },
  ) {
    const q = new URLSearchParams()
    if (params?.page != null) q.set('page', String(params.page))
    if (params?.size != null) q.set('size', String(params.size))
    const suffix = q.toString() ? `?${q.toString()}` : ''
    return api<PagedResponse<RecruitmentParticipantResponse>>(
      `/api/v1/recruitment-listings/${listingId}/participants${suffix}`,
    )
  }

  async function markParticipantAttended(listingId: number, participantId: number) {
    return api<ApiResponse<RecruitmentParticipantResponse>>(
      `/api/v1/recruitment-listings/${listingId}/participants/${participantId}/attend`,
      { method: 'PATCH' },
    )
  }

  // ===========================================
  // 個人マイページ (§9.4)
  // ===========================================

  async function listMyActiveParticipations() {
    return api<ApiResponse<RecruitmentParticipantResponse[]>>('/api/v1/me/recruitment-listings')
  }

  // ===========================================
  // キャンセルポリシー (§9.9)
  // ===========================================

  async function listTeamCancellationPolicies(teamId: number) {
    return api<ApiResponse<CancellationPolicyResponse[]>>(
      `/api/v1/teams/${teamId}/cancellation-policies`,
    )
  }

  async function createCancellationPolicy(teamId: number, body: CreateCancellationPolicyRequest) {
    return api<ApiResponse<CancellationPolicyResponse>>(
      `/api/v1/teams/${teamId}/cancellation-policies`,
      { method: 'POST', body },
    )
  }

  async function getCancellationPolicy(policyId: number) {
    return api<ApiResponse<CancellationPolicyResponse>>(
      `/api/v1/cancellation-policies/${policyId}`,
    )
  }

  async function updateCancellationPolicy(
    policyId: number,
    body: UpdateCancellationPolicyRequest,
  ) {
    return api<ApiResponse<CancellationPolicyResponse>>(
      `/api/v1/cancellation-policies/${policyId}`,
      { method: 'PATCH', body },
    )
  }

  async function archiveCancellationPolicy(policyId: number) {
    return api(`/api/v1/cancellation-policies/${policyId}/archive`, { method: 'POST' })
  }

  // ===========================================
  // 全体検索 (§Phase4)
  // ===========================================

  async function searchListings(params: RecruitmentSearchParams) {
    const q = new URLSearchParams()
    if (params.categoryId != null) q.set('categoryId', String(params.categoryId))
    if (params.subcategoryId != null) q.set('subcategoryId', String(params.subcategoryId))
    if (params.startFrom) q.set('startFrom', params.startFrom)
    if (params.startTo) q.set('startTo', params.startTo)
    if (params.participationType) q.set('participationType', params.participationType)
    if (params.keyword) q.set('keyword', params.keyword)
    if (params.location) q.set('location', params.location)
    if (params.page != null) q.set('page', String(params.page))
    if (params.size != null) q.set('size', String(params.size))
    return api<PagedResponse<RecruitmentListingSummaryResponse>>(
      `/api/v1/recruitment-listings/search?${q.toString()}`,
    )
  }

  // ===========================================
  // Phase 5b: NO_SHOW・ペナルティ
  // ===========================================

  async function markNoShow(scopeType: string, scopeId: number, listingId: number, participantId: number) {
    return api<ApiResponse<RecruitmentNoShowRecordResponse>>(
      `/api/v1/scopes/${scopeType}/${scopeId}/recruitment-listings/${listingId}/participants/${participantId}/no-show`,
      { method: 'POST' },
    )
  }

  async function getNoShowsByScope(scopeType: string, scopeId: number, page = 0, size = 20) {
    return api<PagedResponse<RecruitmentNoShowRecordResponse>>(
      `/api/v1/scopes/${scopeType}/${scopeId}/no-shows?page=${page}&size=${size}`,
    )
  }

  async function getMyNoShows() {
    return api<ApiResponse<RecruitmentNoShowRecordResponse[]>>(
      '/api/v1/recruitment/no-shows/me',
    )
  }

  async function disputeNoShow(noShowId: number, body: DisputeNoShowRequest) {
    return api<ApiResponse<RecruitmentNoShowRecordResponse>>(
      `/api/v1/recruitment/no-shows/${noShowId}/dispute`,
      { method: 'POST', body },
    )
  }

  async function resolveDispute(scopeType: string, scopeId: number, noShowId: number, body: ResolveDisputeRequest) {
    return api<ApiResponse<RecruitmentNoShowRecordResponse>>(
      `/api/v1/scopes/${scopeType}/${scopeId}/no-shows/${noShowId}/dispute`,
      { method: 'PATCH', body },
    )
  }

  async function getPenaltySetting(scopeType: string, scopeId: number) {
    return api<ApiResponse<RecruitmentPenaltySettingResponse>>(
      `/api/v1/scopes/${scopeType}/${scopeId}/penalty-settings`,
    )
  }

  async function upsertPenaltySetting(scopeType: string, scopeId: number, body: UpsertPenaltySettingRequest) {
    return api<ApiResponse<RecruitmentPenaltySettingResponse>>(
      `/api/v1/scopes/${scopeType}/${scopeId}/penalty-settings`,
      { method: 'PUT', body },
    )
  }

  async function getScopePenalties(scopeType: string, scopeId: number, page = 0, size = 20) {
    return api<PagedResponse<RecruitmentUserPenaltyResponse>>(
      `/api/v1/scopes/${scopeType}/${scopeId}/penalties?page=${page}&size=${size}`,
    )
  }

  async function liftPenalty(scopeType: string, scopeId: number, penaltyId: number, body: LiftPenaltyRequest) {
    return api<ApiResponse<RecruitmentUserPenaltyResponse>>(
      `/api/v1/scopes/${scopeType}/${scopeId}/penalties/${penaltyId}/lift`,
      { method: 'POST', body },
    )
  }

  async function getMyPenalties() {
    return api<ApiResponse<RecruitmentUserPenaltyResponse[]>>(
      '/api/v1/recruitment/penalties/me',
    )
  }

  return {
    listCategories,
    listTeamSubcategories,
    createTeamSubcategory,
    archiveTeamSubcategory,
    listTeamListings,
    createListing,
    getListing,
    updateListing,
    publishListing,
    cancelListing,
    archiveListing,
    estimateCancellationFee,
    applyToListing,
    cancelMyApplication,
    listListingParticipants,
    markParticipantAttended,
    listMyActiveParticipations,
    listTeamCancellationPolicies,
    createCancellationPolicy,
    getCancellationPolicy,
    updateCancellationPolicy,
    archiveCancellationPolicy,
    searchListings,
    // Phase 5b: NO_SHOW・ペナルティ
    markNoShow,
    getNoShowsByScope,
    getMyNoShows,
    disputeNoShow,
    resolveDispute,
    getPenaltySetting,
    upsertPenaltySetting,
    getScopePenalties,
    liftPenalty,
    getMyPenalties,
  }
}
