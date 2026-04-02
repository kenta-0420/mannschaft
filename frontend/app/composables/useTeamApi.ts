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

  async function searchTeams(params: {
    keyword?: string
    prefecture?: string
    template?: string
    page?: number
    size?: number
  }) {
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
    return api(`/api/v1/teams/${teamId}/members/${userId}/role`, {
      method: 'PATCH',
      body: { roleId },
    })
  }

  async function removeMember(teamId: number, userId: number) {
    return api(`/api/v1/teams/${teamId}/members/${userId}`, { method: 'DELETE' })
  }

  async function leaveTeam(teamId: number) {
    return api(`/api/v1/teams/${teamId}/me`, { method: 'DELETE' })
  }

  // === 招待トークン ===
  async function createInviteToken(
    teamId: number,
    body: { roleId: number; expiresIn: string | null; maxUses: number | null },
  ) {
    return api<{ data: InviteTokenResponse }>(`/api/v1/teams/${teamId}/invite-tokens`, {
      method: 'POST',
      body,
    })
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

  async function restoreTeam(teamId: number) {
    return api(`/api/v1/teams/${teamId}/restore`, { method: 'PATCH' })
  }

  // === 組織一覧 ===
  async function getOrganizations(teamId: number) {
    return api<{ data: Array<Record<string, unknown>> }>(`/api/v1/teams/${teamId}/organizations`)
  }

  // === フォロー（SUPPORTER） ===
  async function followTeam(teamId: number) {
    return api(`/api/v1/teams/${teamId}/follow`, { method: 'POST' })
  }

  async function unfollowTeam(teamId: number) {
    return api(`/api/v1/teams/${teamId}/follow`, { method: 'DELETE' })
  }

  // === アクセス要件 ===
  async function getAccessRequirements(teamId: number) {
    return api<{ data: Record<string, unknown> }>(`/api/v1/teams/${teamId}/access-requirements`)
  }

  async function updateAccessRequirements(teamId: number, body: Record<string, unknown>) {
    return api<{ data: Record<string, unknown> }>(`/api/v1/teams/${teamId}/access-requirements`, {
      method: 'PUT',
      body,
    })
  }

  // === ブロック管理 ===
  async function getBlocks(teamId: number) {
    return api<{
      data: Array<{
        id: number
        blockedUserId: number
        blockedDisplayName: string
        reason: string | null
        createdAt: string
      }>
    }>(`/api/v1/teams/${teamId}/blocks`)
  }

  async function createBlock(teamId: number, body: { userId: number; reason?: string }) {
    return api(`/api/v1/teams/${teamId}/blocks`, { method: 'POST', body })
  }

  async function removeBlock(teamId: number, userId: number) {
    return api(`/api/v1/teams/${teamId}/blocks/${userId}`, { method: 'DELETE' })
  }

  // === コンテンツ有料化設定 ===
  async function getContentPaymentGates(teamId: number) {
    return api<{
      data: Record<string, unknown>[]
      meta: { page: number; size: number; totalElements: number; totalPages: number }
    }>(`/api/v1/teams/${teamId}/content-payment-gates`)
  }

  async function updateContentPaymentGates(teamId: number, body: Record<string, unknown>) {
    return api(`/api/v1/teams/${teamId}/content-payment-gates`, { method: 'PUT', body })
  }

  // === 権限グループ管理 ===
  async function getPermissionGroups(teamId: number) {
    return api<{
      data: Array<{
        id: number
        name: string
        description: string | null
        permissions: string[]
        createdAt: string
      }>
    }>(`/api/v1/teams/${teamId}/permission-groups`)
  }

  async function createPermissionGroup(
    teamId: number,
    body: { name: string; description?: string; permissions: string[] },
  ) {
    return api(`/api/v1/teams/${teamId}/permission-groups`, { method: 'POST', body })
  }

  async function updatePermissionGroup(
    teamId: number,
    groupId: number,
    body: { name?: string; description?: string; permissions?: string[] },
  ) {
    return api(`/api/v1/teams/${teamId}/permission-groups/${groupId}`, { method: 'PATCH', body })
  }

  async function deletePermissionGroup(teamId: number, groupId: number) {
    return api(`/api/v1/teams/${teamId}/permission-groups/${groupId}`, { method: 'DELETE' })
  }

  async function assignPermissionGroups(teamId: number, userId: number, groupIds: number[]) {
    return api(`/api/v1/teams/${teamId}/members/${userId}/permission-groups`, {
      method: 'PUT',
      body: { groupIds },
    })
  }

  // === オーナー移譲 ===
  async function transferOwnership(teamId: number, newAdminUserId: number) {
    return api(`/api/v1/teams/${teamId}/transfer-ownership`, {
      method: 'POST',
      body: { newAdminUserId },
    })
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
    restoreTeam,
    getOrganizations,
    followTeam,
    unfollowTeam,
    getAccessRequirements,
    updateAccessRequirements,
    getBlocks,
    createBlock,
    removeBlock,
    getContentPaymentGates,
    updateContentPaymentGates,
    getPermissionGroups,
    createPermissionGroup,
    updatePermissionGroup,
    deletePermissionGroup,
    assignPermissionGroups,
    transferOwnership,
    handleApiError,
  }
}
