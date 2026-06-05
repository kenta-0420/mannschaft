interface SupporterResponse {
  userId: number
  fullName: string
  avatarUrl: string | null
  followedAt: string
}

interface SupporterApplicationResponse {
  id: number
  userId: number
  fullName: string
  avatarUrl: string | null
  message: string | null
  status: 'PENDING' | 'APPROVED' | 'REJECTED'
  createdAt: string
}

interface SupporterSettings {
  autoApprove: boolean
}

interface FollowStatusResponse {
  status: 'NONE' | 'PENDING' | 'APPROVED'
}

interface PagedData<T> {
  data: T[]
  meta: { page: number; size: number; totalElements: number; totalPages: number }
}

/**
 * チームのフォロー（SUPPORTER）・サポーター管理・申請管理を扱うサブ composable。
 *
 * useTeamApi を分割した責務マップのうち「フォロー / サポーター」を担当する。
 * 公開関数のシグネチャは元の useTeamApi と同一を維持している。
 */
export function useTeamSupporters() {
  const api = useApi()

  // === フォロー（SUPPORTER） ===
  async function followTeam(teamId: string) {
    return api(`/api/v1/teams/${teamId}/follow`, { method: 'POST' })
  }

  async function unfollowTeam(teamId: string) {
    return api(`/api/v1/teams/${teamId}/follow`, { method: 'DELETE' })
  }

  async function getFollowStatus(teamId: string) {
    return api<{ data: FollowStatusResponse }>(`/api/v1/teams/${teamId}/follow/status`)
  }

  // === サポーター管理（管理者） ===
  async function getSupporters(teamId: string, params?: { page?: number; size?: number }) {
    const query = new URLSearchParams()
    query.set('page', String(params?.page ?? 0))
    query.set('size', String(params?.size ?? 50))
    return api<PagedData<SupporterResponse>>(`/api/v1/teams/${teamId}/supporters?${query}`)
  }

  async function getSupporterApplications(
    teamId: string,
    params?: { page?: number; size?: number },
  ) {
    const query = new URLSearchParams()
    query.set('page', String(params?.page ?? 0))
    query.set('size', String(params?.size ?? 50))
    return api<PagedData<SupporterApplicationResponse>>(
      `/api/v1/teams/${teamId}/supporter-applications?${query}`,
    )
  }

  async function approveSupporterApplication(teamId: string, applicationId: number) {
    return api(`/api/v1/teams/${teamId}/supporter-applications/${applicationId}/approve`, {
      method: 'POST',
    })
  }

  async function rejectSupporterApplication(teamId: string, applicationId: number) {
    return api(`/api/v1/teams/${teamId}/supporter-applications/${applicationId}/reject`, {
      method: 'POST',
    })
  }

  async function bulkApproveSupporterApplications(teamId: string, applicationIds: number[]) {
    return api(`/api/v1/teams/${teamId}/supporter-applications/bulk-approve`, {
      method: 'POST',
      body: { applicationIds },
    })
  }

  async function getSupporterSettings(teamId: string) {
    return api<{ data: SupporterSettings }>(`/api/v1/teams/${teamId}/supporter-settings`)
  }

  async function updateSupporterSettings(teamId: string, body: Partial<SupporterSettings>) {
    return api<{ data: SupporterSettings }>(`/api/v1/teams/${teamId}/supporter-settings`, {
      method: 'PUT',
      body,
    })
  }

  return {
    followTeam,
    unfollowTeam,
    getFollowStatus,
    getSupporters,
    getSupporterApplications,
    approveSupporterApplication,
    rejectSupporterApplication,
    bulkApproveSupporterApplications,
    getSupporterSettings,
    updateSupporterSettings,
  }
}
