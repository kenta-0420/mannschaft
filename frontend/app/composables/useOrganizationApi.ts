interface OrganizationResponse {
  id: number
  name: string
  nameKana: string | null
  nickname1: string | null
  nickname2: string | null
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

interface OrganizationSummaryResponse {
  id: number
  name: string
  nickname1: string | null
  iconUrl: string | null
  prefecture: string | null
  city: string | null
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

interface PermissionGroupResponse {
  id: number
  name: string
  description: string | null
  permissions: string[]
  createdAt: string
}

interface BlockResponse {
  id: number
  blockedUserId: number
  blockedDisplayName: string
  reason: string | null
  createdAt: string
}

interface TeamSummaryResponse {
  id: number
  name: string
  nickname1: string | null
  iconUrl: string | null
  template: string
  memberCount: number
}

interface PagedData<T> {
  data: T[]
  meta: { page: number; size: number; totalElements: number; totalPages: number }
}

export function useOrganizationApi() {
  const api = useApi()
  const { handleApiError } = useErrorHandler()

  // === CRUD ===
  async function getOrganization(orgId: number) {
    return api<{ data: OrganizationResponse }>(`/api/v1/organizations/${orgId}`)
  }

  async function searchOrganizations(params: {
    keyword?: string
    prefecture?: string
    page?: number
    size?: number
  }) {
    const query = new URLSearchParams()
    if (params.keyword) query.set('keyword', params.keyword)
    if (params.prefecture) query.set('prefecture', params.prefecture)
    query.set('page', String(params.page ?? 0))
    query.set('size', String(params.size ?? 20))
    return api<PagedData<OrganizationSummaryResponse>>(`/api/v1/organizations/search?${query}`)
  }

  async function createOrganization(body: Record<string, unknown>) {
    return api<{ data: OrganizationResponse }>('/api/v1/organizations', { method: 'POST', body })
  }

  async function updateOrganization(orgId: number, body: Record<string, unknown>) {
    return api<{ data: OrganizationResponse }>(`/api/v1/organizations/${orgId}`, {
      method: 'PATCH',
      body,
    })
  }

  async function deleteOrganization(orgId: number) {
    return api(`/api/v1/organizations/${orgId}`, { method: 'DELETE' })
  }

  // === メンバー管理 ===
  async function getMembers(orgId: number, params?: { page?: number; size?: number }) {
    const query = new URLSearchParams()
    query.set('page', String(params?.page ?? 0))
    query.set('size', String(params?.size ?? 20))
    return api<PagedData<MemberResponse>>(`/api/v1/organizations/${orgId}/members?${query}`)
  }

  async function changeRole(orgId: number, userId: number, roleId: number) {
    return api(`/api/v1/organizations/${orgId}/members/${userId}/role`, {
      method: 'PATCH',
      body: { roleId },
    })
  }

  async function removeMember(orgId: number, userId: number) {
    return api(`/api/v1/organizations/${orgId}/members/${userId}`, { method: 'DELETE' })
  }

  async function leaveOrganization(orgId: number) {
    return api(`/api/v1/organizations/${orgId}/me`, { method: 'DELETE' })
  }

  // === 招待トークン ===
  async function createInviteToken(
    orgId: number,
    body: { roleId: number; expiresIn: string | null; maxUses: number | null },
  ) {
    return api<{ data: InviteTokenResponse }>(`/api/v1/organizations/${orgId}/invite-tokens`, {
      method: 'POST',
      body,
    })
  }

  async function getInviteTokens(orgId: number) {
    return api<{ data: InviteTokenResponse[] }>(`/api/v1/organizations/${orgId}/invite-tokens`)
  }

  async function deleteInviteToken(orgId: number, tokenId: number) {
    return api(`/api/v1/organizations/${orgId}/invite-tokens/${tokenId}`, { method: 'DELETE' })
  }

  // === アーカイブ ===
  async function archiveOrganization(orgId: number) {
    return api(`/api/v1/organizations/${orgId}/archive`, { method: 'PATCH' })
  }

  async function unarchiveOrganization(orgId: number) {
    return api(`/api/v1/organizations/${orgId}/unarchive`, { method: 'PATCH' })
  }

  async function restoreOrganization(orgId: number) {
    return api(`/api/v1/organizations/${orgId}/restore`, { method: 'PATCH' })
  }

  // === 全メンバー一覧 ===
  async function getAllMembers(orgId: number) {
    return api<{ data: MemberResponse[] }>(`/api/v1/organizations/${orgId}/members/all`)
  }

  // === フォロー（SUPPORTER） ===
  async function followOrganization(orgId: number) {
    return api(`/api/v1/organizations/${orgId}/follow`, { method: 'POST' })
  }

  async function unfollowOrganization(orgId: number) {
    return api(`/api/v1/organizations/${orgId}/follow`, { method: 'DELETE' })
  }

  // === 権限グループ管理 ===
  async function getPermissionGroups(orgId: number) {
    return api<{ data: PermissionGroupResponse[] }>(
      `/api/v1/organizations/${orgId}/permission-groups`,
    )
  }

  async function createPermissionGroup(
    orgId: number,
    body: { name: string; description?: string; permissions: string[] },
  ) {
    return api<{ data: PermissionGroupResponse }>(
      `/api/v1/organizations/${orgId}/permission-groups`,
      { method: 'POST', body },
    )
  }

  async function updatePermissionGroup(
    orgId: number,
    groupId: number,
    body: { name?: string; description?: string; permissions?: string[] },
  ) {
    return api<{ data: PermissionGroupResponse }>(
      `/api/v1/organizations/${orgId}/permission-groups/${groupId}`,
      { method: 'PATCH', body },
    )
  }

  async function deletePermissionGroup(orgId: number, groupId: number) {
    return api(`/api/v1/organizations/${orgId}/permission-groups/${groupId}`, { method: 'DELETE' })
  }

  async function assignPermissionGroups(orgId: number, userId: number, groupIds: number[]) {
    return api(`/api/v1/organizations/${orgId}/members/${userId}/permission-groups`, {
      method: 'PUT',
      body: { groupIds },
    })
  }

  // === ブロック管理 ===
  async function getBlocks(orgId: number) {
    return api<{ data: BlockResponse[] }>(`/api/v1/organizations/${orgId}/blocks`)
  }

  async function createBlock(orgId: number, body: { userId: number; reason?: string }) {
    return api<{ data: BlockResponse }>(`/api/v1/organizations/${orgId}/blocks`, {
      method: 'POST',
      body,
    })
  }

  async function removeBlock(orgId: number, blockId: number) {
    return api(`/api/v1/organizations/${orgId}/blocks/${blockId}`, { method: 'DELETE' })
  }

  // === オーナー移譲 ===
  async function transferOwnership(orgId: number, newAdminUserId: number) {
    return api(`/api/v1/organizations/${orgId}/transfer-ownership`, {
      method: 'POST',
      body: { newAdminUserId },
    })
  }

  // === アクセス要件 ===
  async function getAccessRequirements(orgId: number) {
    return api<{ data: Record<string, unknown> }>(
      `/api/v1/organizations/${orgId}/access-requirements`,
    )
  }

  async function updateAccessRequirements(orgId: number, body: Record<string, unknown>) {
    return api<{ data: Record<string, unknown> }>(
      `/api/v1/organizations/${orgId}/access-requirements`,
      { method: 'PUT', body },
    )
  }

  // === コンテンツ有料化設定 ===
  async function getContentPaymentGates(orgId: number) {
    return api<{
      data: Record<string, unknown>[]
      meta: { page: number; size: number; totalElements: number; totalPages: number }
    }>(`/api/v1/organizations/${orgId}/content-payment-gates`)
  }

  async function updateContentPaymentGates(orgId: number, body: Record<string, unknown>) {
    return api(`/api/v1/organizations/${orgId}/content-payment-gates`, { method: 'PUT', body })
  }

  // === 組織内チーム一覧 ===
  async function getTeamsInOrg(orgId: number) {
    return api<{ data: TeamSummaryResponse[] }>(`/api/v1/organizations/${orgId}/teams`)
  }

  return {
    getOrganization,
    searchOrganizations,
    createOrganization,
    updateOrganization,
    deleteOrganization,
    getMembers,
    changeRole,
    removeMember,
    leaveOrganization,
    createInviteToken,
    getInviteTokens,
    deleteInviteToken,
    archiveOrganization,
    unarchiveOrganization,
    restoreOrganization,
    getAllMembers,
    followOrganization,
    unfollowOrganization,
    getPermissionGroups,
    createPermissionGroup,
    updatePermissionGroup,
    deletePermissionGroup,
    assignPermissionGroups,
    getBlocks,
    createBlock,
    removeBlock,
    transferOwnership,
    getAccessRequirements,
    updateAccessRequirements,
    getContentPaymentGates,
    updateContentPaymentGates,
    getTeamsInOrg,
    handleApiError,
  }
}
