import type {
  ApplyToRecruitmentRequest,
  CancelMyApplicationRequest,
  RecruitmentDistributionTargetResponse,
  RecruitmentFeedItem,
  RecruitmentMyListingItem,
  RecruitmentParticipantResponse,
  SetDistributionTargetsRequest,
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
 * F03.11 募集型予約 — 参加申込・配信・マイページ系 API クライアント。
 *
 * 担当範囲:
 *   - 参加申込 (§9.2, §9.10)
 *   - 個人マイページ (§9.4) + Phase 2 フィード
 *   - 配信対象設定 (Phase 2 §9.3)
 *   - 申込確定 (Phase 2)
 */
export function useRecruitmentApplications() {
  const api = useApi()

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
  // 個人マイページ (§9.4) + Phase 2 フィード
  // ===========================================

  async function listMyActiveParticipations() {
    return api<ApiResponse<RecruitmentParticipantResponse[]>>('/api/v1/me/recruitment-listings')
  }

  /** Phase 2: 自分の参加予定一覧 */
  async function getMyListings() {
    return api<ApiResponse<RecruitmentMyListingItem[]>>('/api/v1/me/recruitment-listings')
  }

  /** Phase 2: フォロー先・サポーター先の新着募集フィード */
  async function getMyFeed() {
    return api<ApiResponse<RecruitmentFeedItem[]>>('/api/v1/me/recruitment-feed')
  }

  // ===========================================
  // Phase 2: 配信対象設定 (§9.3)
  // ===========================================

  async function getDistributionTargets(listingId: number) {
    return api<ApiResponse<RecruitmentDistributionTargetResponse[]>>(
      `/api/v1/recruitment-listings/${listingId}/distribution-targets`,
    )
  }

  async function setDistributionTargets(listingId: number, body: SetDistributionTargetsRequest) {
    return api<ApiResponse<RecruitmentDistributionTargetResponse[]>>(
      `/api/v1/recruitment-listings/${listingId}/distribution-targets`,
      { method: 'PUT', body },
    )
  }

  /** Phase 2: 申込確定 (APPLIED → CONFIRMED) */
  async function confirmApplication(listingId: number, participantId: number) {
    return api<ApiResponse<RecruitmentParticipantResponse>>(
      `/api/v1/recruitment-listings/${listingId}/participants/${participantId}/confirm`,
      { method: 'POST' },
    )
  }

  return {
    applyToListing,
    cancelMyApplication,
    listListingParticipants,
    markParticipantAttended,
    listMyActiveParticipations,
    getMyListings,
    getMyFeed,
    getDistributionTargets,
    setDistributionTargets,
    confirmApplication,
  }
}
