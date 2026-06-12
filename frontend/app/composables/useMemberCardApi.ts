import type {
  MemberCard,
  MemberCardListItem,
  MemberCardStatus,
  MemberCardQr,
  CheckinRecord,
  CheckinStats,
  CheckinHistoryRecord,
  CheckinLocation,
  VerifyResponse,
  CreateCheckinLocationRequest,
} from '~/types/member-card'

export function useMemberCardApi() {
  const api = useApi()

  async function listMy() {
    const res = await api<{ data: MemberCard[] }>('/api/v1/member-cards/my')
    return res.data
  }

  async function get(id: number) {
    const res = await api<{ data: MemberCard }>(`/api/v1/member-cards/${id}`)
    return res.data
  }

  async function getQr(id: number) {
    const res = await api<{ data: MemberCardQr }>(`/api/v1/member-cards/${id}/qr`)
    return res.data
  }

  async function getCheckins(id: number, params?: { page?: number; size?: number }) {
    const query = new URLSearchParams()
    if (params?.page != null) query.set('page', String(params.page))
    if (params?.size != null) query.set('size', String(params.size))
    const qs = query.toString()
    const res = await api<{ data: CheckinRecord[]; meta: { totalElements: number } }>(
      `/api/v1/member-cards/${id}/checkins${qs ? `?${qs}` : ''}`,
    )
    return res
  }

  async function regenerate(id: number) {
    const res = await api<{ data: MemberCardQr }>(`/api/v1/member-cards/${id}/regenerate`, {
      method: 'POST',
    })
    return res.data
  }

  async function verify(token: string) {
    const res = await api<{ data: VerifyResponse }>('/api/v1/member-cards/verify', {
      method: 'POST',
      body: { token },
    })
    return res.data
  }

  async function selfCheckin(locationCode: string) {
    const res = await api<{ data: VerifyResponse }>('/api/v1/member-cards/self-checkin', {
      method: 'POST',
      body: { locationCode },
    })
    return res.data
  }

  async function suspend(id: number) {
    await api(`/api/v1/member-cards/${id}/suspend`, { method: 'PATCH' })
  }

  async function reactivate(id: number) {
    await api(`/api/v1/member-cards/${id}/reactivate`, { method: 'PATCH' })
  }

  async function listByTeam(teamId: string) {
    const res = await api<{ data: MemberCard[] }>(`/api/v1/teams/${teamId}/member-cards`)
    return res.data
  }

  async function listByOrg(orgId: string) {
    const res = await api<{ data: MemberCard[] }>(`/api/v1/organizations/${orgId}/member-cards`)
    return res.data
  }

  /**
   * 組織の会員証一覧を氏名検索付きで取得する（メンバー選択 UI 向け）。
   * BE: GET /api/v1/organizations/{orgId}/member-cards?status=&q=（MemberCardListResponse[]・displayName 同梱）。
   * status 既定は ACTIVE。q は会員名/カード番号の部分一致検索。
   */
  async function searchOrgMembers(
    orgId: string,
    params?: { q?: string; status?: MemberCardStatus },
  ) {
    const query = new URLSearchParams()
    if (params?.status) query.set('status', params.status)
    if (params?.q != null && params.q.trim() !== '') query.set('q', params.q.trim())
    const qs = query.toString()
    const res = await api<{ data: MemberCardListItem[] }>(
      `/api/v1/organizations/${orgId}/member-cards${qs ? `?${qs}` : ''}`,
    )
    return res.data
  }

  async function getTeamCheckins(teamId: string, params?: { from?: string; to?: string }) {
    const query = new URLSearchParams()
    if (params?.from) query.set('from', params.from)
    if (params?.to) query.set('to', params.to)
    const qs = query.toString()
    const res = await api<{ data: CheckinHistoryRecord[] }>(
      `/api/v1/teams/${teamId}/checkins${qs ? `?${qs}` : ''}`,
    )
    return res.data
  }

  async function getTeamCheckinStats(teamId: string) {
    const res = await api<{ data: CheckinStats }>(`/api/v1/teams/${teamId}/checkins/stats`)
    return res.data
  }

  async function listLocations(teamId: string) {
    const res = await api<{ data: CheckinLocation[] }>(`/api/v1/teams/${teamId}/checkin-locations`)
    return res.data
  }

  async function createLocation(teamId: string, body: CreateCheckinLocationRequest) {
    const res = await api<{ data: CheckinLocation }>(`/api/v1/teams/${teamId}/checkin-locations`, {
      method: 'POST',
      body,
    })
    return res.data
  }

  async function updateLocation(teamId: string, id: number, body: CreateCheckinLocationRequest) {
    const res = await api<{ data: CheckinLocation }>(
      `/api/v1/teams/${teamId}/checkin-locations/${id}`,
      { method: 'PUT', body },
    )
    return res.data
  }

  async function deleteLocation(teamId: string, id: number) {
    await api(`/api/v1/teams/${teamId}/checkin-locations/${id}`, { method: 'DELETE' })
  }

  async function getLocationQr(teamId: string, id: number) {
    const res = await api<{ data: { qrToken: string } }>(
      `/api/v1/teams/${teamId}/checkin-locations/${id}/qr`,
    )
    return res.data
  }

  return {
    listMy,
    get,
    getQr,
    getCheckins,
    regenerate,
    verify,
    selfCheckin,
    suspend,
    reactivate,
    listByTeam,
    listByOrg,
    searchOrgMembers,
    getTeamCheckins,
    getTeamCheckinStats,
    listLocations,
    createLocation,
    updateLocation,
    deleteLocation,
    getLocationQr,
  }
}
