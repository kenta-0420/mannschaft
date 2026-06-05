import type {
  DisputeNoShowRequest,
  LiftPenaltyRequest,
  RecruitmentNoShowRecordResponse,
  RecruitmentPenaltySettingResponse,
  RecruitmentUserPenaltyResponse,
  ResolveDisputeRequest,
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
 * F03.11 募集型予約 — NO_SHOW・ペナルティ系 API クライアント (Phase 5b)。
 *
 * 担当範囲:
 *   - NO_SHOW 記録 / 異議申立 / 裁定
 *   - ペナルティ設定 (Upsert)
 *   - ペナルティ一覧 / 解除 / 自分のペナルティ
 */
export function useRecruitmentMatching() {
  const api = useApi()

  async function markNoShow(scopeType: string, scopeId: string, listingId: number, participantId: number) {
    return api<ApiResponse<RecruitmentNoShowRecordResponse>>(
      `/api/v1/scopes/${scopeType}/${scopeId}/recruitment-listings/${listingId}/participants/${participantId}/no-show`,
      { method: 'POST' },
    )
  }

  async function getNoShowsByScope(scopeType: string, scopeId: string, page = 0, size = 20) {
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

  async function resolveDispute(scopeType: string, scopeId: string, noShowId: number, body: ResolveDisputeRequest) {
    return api<ApiResponse<RecruitmentNoShowRecordResponse>>(
      `/api/v1/scopes/${scopeType}/${scopeId}/no-shows/${noShowId}/dispute`,
      { method: 'PATCH', body },
    )
  }

  async function getPenaltySetting(scopeType: string, scopeId: string) {
    return api<ApiResponse<RecruitmentPenaltySettingResponse>>(
      `/api/v1/scopes/${scopeType}/${scopeId}/penalty-settings`,
    )
  }

  async function upsertPenaltySetting(scopeType: string, scopeId: string, body: UpsertPenaltySettingRequest) {
    return api<ApiResponse<RecruitmentPenaltySettingResponse>>(
      `/api/v1/scopes/${scopeType}/${scopeId}/penalty-settings`,
      { method: 'PUT', body },
    )
  }

  async function getScopePenalties(scopeType: string, scopeId: string, page = 0, size = 20) {
    return api<PagedResponse<RecruitmentUserPenaltyResponse>>(
      `/api/v1/scopes/${scopeType}/${scopeId}/penalties?page=${page}&size=${size}`,
    )
  }

  async function liftPenalty(scopeType: string, scopeId: string, penaltyId: number, body: LiftPenaltyRequest) {
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
