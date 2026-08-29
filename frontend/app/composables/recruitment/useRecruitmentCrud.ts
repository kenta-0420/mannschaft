import type {
  CancelRecruitmentListingRequest,
  CancellationFeeEstimateResponse,
  CancellationPolicyResponse,
  CreateCancellationPolicyRequest,
  CreateRecruitmentListingRequest,
  CreateRecruitmentSubcategoryRequest,
  RecruitmentCategoryResponse,
  RecruitmentListingResponse,
  RecruitmentListingSummaryResponse,
  RecruitmentSearchParams,
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
 * F03.11 募集型予約 — CRUD・カタログ系 API クライアント。
 *
 * 担当範囲:
 *   - カテゴリ (§9.7)
 *   - サブカテゴリ (§9.6)
 *   - 募集枠 CRUD (§9.1)
 *   - キャンセル料見積
 *   - キャンセルポリシー (§9.9)
 *   - 全体検索 (Phase 4)
 */
export function useRecruitmentCrud() {
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

  async function listTeamSubcategories(teamId: string, categoryId?: number) {
    const q = new URLSearchParams()
    if (categoryId != null) q.set('categoryId', String(categoryId))
    const suffix = q.toString() ? `?${q.toString()}` : ''
    return api<ApiResponse<RecruitmentSubcategoryResponse[]>>(
      `/api/v1/teams/${teamId}/recruitment-subcategories${suffix}`,
    )
  }

  async function createTeamSubcategory(teamId: string, body: CreateRecruitmentSubcategoryRequest) {
    return api<ApiResponse<RecruitmentSubcategoryResponse>>(
      `/api/v1/teams/${teamId}/recruitment-subcategories`,
      { method: 'POST', body },
    )
  }

  async function archiveTeamSubcategory(teamId: string, subcategoryId: number) {
    return api(`/api/v1/teams/${teamId}/recruitment-subcategories/${subcategoryId}/archive`, {
      method: 'POST',
    })
  }

  // ===========================================
  // 募集枠 CRUD (§9.1)
  // ===========================================

  async function listTeamListings(
    teamId: string,
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

  async function listOrganizationListings(
    orgId: string,
    params?: { status?: string; page?: number; size?: number },
  ) {
    const q = new URLSearchParams()
    if (params?.status) q.set('status', params.status)
    if (params?.page != null) q.set('page', String(params.page))
    if (params?.size != null) q.set('size', String(params.size))
    const suffix = q.toString() ? `?${q.toString()}` : ''
    return api<PagedResponse<RecruitmentListingSummaryResponse>>(
      `/api/v1/organizations/${orgId}/recruitment-listings${suffix}`,
    )
  }

  async function createListing(teamId: string, body: CreateRecruitmentListingRequest) {
    return api<ApiResponse<RecruitmentListingResponse>>(
      `/api/v1/teams/${teamId}/recruitment-listings`,
      { method: 'POST', body },
    )
  }

  async function createOrgListing(orgId: string, body: CreateRecruitmentListingRequest) {
    return api<ApiResponse<RecruitmentListingResponse>>(
      `/api/v1/organizations/${orgId}/recruitment-listings`,
      { method: 'POST', body },
    )
  }

  async function getListing(listingId: number) {
    return api<ApiResponse<RecruitmentListingResponse>>(`/api/v1/recruitment-listings/${listingId}`)
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
  // キャンセルポリシー (§9.9)
  // ===========================================

  async function listTeamCancellationPolicies(teamId: string) {
    return api<ApiResponse<CancellationPolicyResponse[]>>(
      `/api/v1/teams/${teamId}/cancellation-policies`,
    )
  }

  async function createCancellationPolicy(teamId: string, body: CreateCancellationPolicyRequest) {
    return api<ApiResponse<CancellationPolicyResponse>>(
      `/api/v1/teams/${teamId}/cancellation-policies`,
      { method: 'POST', body },
    )
  }

  async function getCancellationPolicy(policyId: number) {
    return api<ApiResponse<CancellationPolicyResponse>>(`/api/v1/cancellation-policies/${policyId}`)
  }

  async function updateCancellationPolicy(policyId: number, body: UpdateCancellationPolicyRequest) {
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

  return {
    listCategories,
    listTeamSubcategories,
    createTeamSubcategory,
    archiveTeamSubcategory,
    listTeamListings,
    listOrganizationListings,
    createListing,
    createOrgListing,
    getListing,
    updateListing,
    publishListing,
    cancelListing,
    archiveListing,
    estimateCancellationFee,
    listTeamCancellationPolicies,
    createCancellationPolicy,
    getCancellationPolicy,
    updateCancellationPolicy,
    archiveCancellationPolicy,
    searchListings,
  }
}
