import type { FetchError } from 'ofetch'
import type { PagedResponse } from '~/types/api'
import type { MemberResponse } from '~/types/member'
import type { TeamResponse } from '~/types/team'
import {
  OrganizationNotFoundError,
  TeamSearchRateLimitError,
  type TeamSearchItem,
  type TeamSearchQuery,
} from '~/types/team-search'

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

  /**
   * F15.4 組織内チーム（店舗）検索。
   * 指定された組織に属するチームを keyword / prefecture / city / template で絞り込む。
   *
   * - undefined のクエリパラメータは URL から除外される
   * - 404: 組織が存在しない → {@link OrganizationNotFoundError}
   * - 429: レート制限超過 → {@link TeamSearchRateLimitError}
   * - その他のエラーはそのままスロー（ofetch の `FetchError`）
   *
   * @param orgId 組織 ID
   * @param query 検索クエリ
   * @returns ページング済みの検索結果。閲覧者の権限に応じて要素は
   *          `TeamPublicSummary` または `TeamSearchResult` の union 型になる
   *          （判定は `isTeamSearchResult` タイプガードで行う）。
   */
  async function searchOrganizationTeams(
    orgId: number | string,
    query: TeamSearchQuery,
  ): Promise<PagedResponse<TeamSearchItem>> {
    const params = new URLSearchParams()
    if (query.keyword !== undefined) params.set('keyword', query.keyword)
    if (query.prefecture !== undefined) params.set('prefecture', query.prefecture)
    if (query.city !== undefined) params.set('city', query.city)
    if (query.template !== undefined) params.set('template', query.template)
    if (query.page !== undefined) params.set('page', String(query.page))
    if (query.size !== undefined) params.set('size', String(query.size))
    if (query.sort !== undefined) params.set('sort', query.sort)

    const qs = params.toString()
    const url =
      `/api/v1/organizations/${orgId}/teams/search` + (qs.length > 0 ? `?${qs}` : '')

    try {
      return await api<PagedResponse<TeamSearchItem>>(url)
    } catch (error) {
      const fetchError = error as FetchError
      const status = fetchError?.response?.status
      if (status === 404) {
        throw new OrganizationNotFoundError(orgId)
      }
      if (status === 429) {
        const retryAfter = fetchError.response?.headers.get('Retry-After')
        const retryAfterSeconds =
          retryAfter !== null && retryAfter !== undefined && retryAfter !== ''
            ? Number.parseInt(retryAfter, 10)
            : null
        throw new TeamSearchRateLimitError(
          Number.isFinite(retryAfterSeconds) ? retryAfterSeconds : null,
        )
      }
      throw error
    }
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

  async function getFollowStatus(teamId: number) {
    return api<{ data: FollowStatusResponse }>(`/api/v1/teams/${teamId}/follow/status`)
  }

  // === サポーター管理（管理者） ===
  async function getSupporters(teamId: number, params?: { page?: number; size?: number }) {
    const query = new URLSearchParams()
    query.set('page', String(params?.page ?? 0))
    query.set('size', String(params?.size ?? 50))
    return api<PagedData<SupporterResponse>>(`/api/v1/teams/${teamId}/supporters?${query}`)
  }

  async function getSupporterApplications(
    teamId: number,
    params?: { page?: number; size?: number },
  ) {
    const query = new URLSearchParams()
    query.set('page', String(params?.page ?? 0))
    query.set('size', String(params?.size ?? 50))
    return api<PagedData<SupporterApplicationResponse>>(
      `/api/v1/teams/${teamId}/supporter-applications?${query}`,
    )
  }

  async function approveSupporterApplication(teamId: number, applicationId: number) {
    return api(`/api/v1/teams/${teamId}/supporter-applications/${applicationId}/approve`, {
      method: 'POST',
    })
  }

  async function rejectSupporterApplication(teamId: number, applicationId: number) {
    return api(`/api/v1/teams/${teamId}/supporter-applications/${applicationId}/reject`, {
      method: 'POST',
    })
  }

  async function bulkApproveSupporterApplications(teamId: number, applicationIds: number[]) {
    return api(`/api/v1/teams/${teamId}/supporter-applications/bulk-approve`, {
      method: 'POST',
      body: { applicationIds },
    })
  }

  async function getSupporterSettings(teamId: number) {
    return api<{ data: SupporterSettings }>(`/api/v1/teams/${teamId}/supporter-settings`)
  }

  async function updateSupporterSettings(teamId: number, body: Partial<SupporterSettings>) {
    return api<{ data: SupporterSettings }>(`/api/v1/teams/${teamId}/supporter-settings`, {
      method: 'PUT',
      body,
    })
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
    searchOrganizationTeams,
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
    getFollowStatus,
    getSupporters,
    getSupporterApplications,
    approveSupporterApplication,
    rejectSupporterApplication,
    bulkApproveSupporterApplications,
    getSupporterSettings,
    updateSupporterSettings,
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
