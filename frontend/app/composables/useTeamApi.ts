interface TeamResponse {
  id: number
  name: string
  nameKana: string | null
  nickname1: string | null
  nickname2: string | null
  template: string
  prefecture: string | null
  city: string | null
  description: string | null
  visibility: string
  supporterEnabled: boolean
  version: number
  memberCount: number
  archivedAt: string | null
  createdAt: string
}

interface TeamSummaryResponse {
  id: number
  name: string
  nickname1: string | null
  iconUrl: string | null
  prefecture: string | null
  city: string | null
  template: string
  memberCount: number
  supporterEnabled: boolean
}

interface MemberResponse {
  userId: number
  displayName: string
  avatarUrl: string | null
  roleName: string
  joinedAt: string
}

interface InviteTokenResponse {
  id: number
  token: string
  roleName: string
  expiresAt: string | null
  maxUses: number | null
  usedCount: number
  revokedAt: string | null
  createdAt: string
}

interface PagedData<T> {
  data: T[]
  meta: { page: number; size: number; totalElements: number; totalPages: number }
}

export function useTeamApi() {
  const api = useApi()
  const { handleApiError } = useErrorHandler()

  // === CRUD ===
  async function getTeam(teamId: number) {
    return api<{ data: TeamResponse }>(`/api/v1/teams/${teamId}`)
  }

  async function searchTeams(params: { keyword?: string; prefecture?: string; template?: string; page?: number; size?: number }) {
    const query = new URLSearchParams()
    if (params.keyword) query.set('keyword', params.keyword)
    if (params.prefecture) query.set('prefecture', params.prefecture)
    if (params.template) query.set('template', params.template)
    query.set('page', String(params.page ?? 0))
    query.set('size', String(params.size ?? 20))
    return api<PagedData<TeamSummaryResponse>>(`/api/v1/teams/search?${query}`)
  }

  async function createTeam(body: Record<string, unknown>) {
    return api<{ data: TeamResponse }>('/api/v1/teams', { method: 'POST', body })
  }

  async function updateTeam(teamId: number, body: Record<string, unknown>) {
    return api<{ data: TeamResponse }>(`/api/v1/teams/${teamId}`, { method: 'PATCH', body })
  }

  async function deleteTeam(teamId: number) {
    return api(`/api/v1/teams/${teamId}`, { method: 'DELETE' })
  }

  // === メンバー管理 ===
  async function getMembers(teamId: number, params?: { page?: number; size?: number }) {
    const query = new URLSearchParams()
    query.set('page', String(params?.page ?? 0))
    query.set('size', String(params?.size ?? 20))
    return api<PagedData<MemberResponse>>(`/api/v1/teams/${teamId}/members?${query}`)
  }

  async function changeRole(teamId: number, userId: number, roleId: number) {
    return api(`/api/v1/teams/${teamId}/members/${userId}/role`, { method: 'PATCH', body: { roleId } })
  }

  async function removeMember(teamId: number, userId: number) {
    return api(`/api/v1/teams/${teamId}/members/${userId}`, { method: 'DELETE' })
  }

  async function leaveTeam(teamId: number) {
    return api(`/api/v1/teams/${teamId}/me`, { method: 'DELETE' })
  }

  // === 招待トークン ===
  async function createInviteToken(teamId: number, body: { roleId: number; expiresIn: string | null; maxUses: number | null }) {
    return api<{ data: InviteTokenResponse }>(`/api/v1/teams/${teamId}/invite-tokens`, { method: 'POST', body })
  }

  async function getInviteTokens(teamId: number) {
    return api<{ data: InviteTokenResponse[] }>(`/api/v1/teams/${teamId}/invite-tokens`)
  }

  async function deleteInviteToken(teamId: number, tokenId: number) {
    return api(`/api/v1/teams/${teamId}/invite-tokens/${tokenId}`, { method: 'DELETE' })
  }

  // === アーカイブ ===
  async function archiveTeam(teamId: number) {
    return api(`/api/v1/teams/${teamId}/archive`, { method: 'PATCH' })
  }

  async function unarchiveTeam(teamId: number) {
    return api(`/api/v1/teams/${teamId}/unarchive`, { method: 'PATCH' })
  }

  // === フォロー（SUPPORTER） ===
  async function followTeam(teamId: number) {
    return api(`/api/v1/teams/${teamId}/follow`, { method: 'POST' })
  }

  async function unfollowTeam(teamId: number) {
    return api(`/api/v1/teams/${teamId}/follow`, { method: 'DELETE' })
  }

  return {
    getTeam,
    searchTeams,
    createTeam,
    updateTeam,
    deleteTeam,
    getMembers,
    changeRole,
    removeMember,
    leaveTeam,
    createInviteToken,
    getInviteTokens,
    deleteInviteToken,
    archiveTeam,
    unarchiveTeam,
    followTeam,
    unfollowTeam,
    handleApiError,
  }
}
