import type {
  ApplyToRecruitmentRequest,
  CancelMyApplicationRequest,
  CancelRecruitmentListingRequest,
  CancellationFeeEstimateResponse,
  CancellationPolicyResponse,
  CreateCancellationPolicyRequest,
  CreateRecruitmentListingRequest,
  CreateRecruitmentSubcategoryRequest,
  RecruitmentCategoryResponse,
  RecruitmentListingResponse,
  RecruitmentListingSummaryResponse,
  RecruitmentParticipantResponse,
  RecruitmentSubcategoryResponse,
  UpdateCancellationPolicyRequest,
  UpdateRecruitmentListingRequest,
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
  }
}
