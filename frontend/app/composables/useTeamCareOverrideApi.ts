import type {
  TeamCareOverrideRequest,
  TeamCareOverrideResponse,
} from '~/types/teamCareOverride'

interface ApiResponse<T> {
  data: T
}

/**
 * F03.12 チーム別ケア通知上書き API クライアント。
 * /api/v1/teams/{teamId}/care-overrides を扱う。
 */
export function useTeamCareOverrideApi() {
  const api = useApi()

  // ===========================================
  // チーム別通知上書き取得
  // ===========================================

  async function getCareOverride(teamId: number, careLinkId: number) {
    return api<ApiResponse<TeamCareOverrideResponse>>(
      `/api/v1/teams/${teamId}/care-overrides/${careLinkId}`,
    )
  }

  // ===========================================
  // チーム別通知上書き upsert
  // ===========================================

  async function upsertCareOverride(
    teamId: number,
    careLinkId: number,
    body: TeamCareOverrideRequest,
  ) {
    return api<ApiResponse<TeamCareOverrideResponse>>(
      `/api/v1/teams/${teamId}/care-overrides/${careLinkId}`,
      { method: 'PUT', body },
    )
  }

  // ===========================================
  // チーム別通知上書き削除
  // ===========================================

  async function deleteCareOverride(teamId: number, careLinkId: number) {
    return api(`/api/v1/teams/${teamId}/care-overrides/${careLinkId}`, { method: 'DELETE' })
  }

  return {
    getCareOverride,
    upsertCareOverride,
    deleteCareOverride,
  }
}
