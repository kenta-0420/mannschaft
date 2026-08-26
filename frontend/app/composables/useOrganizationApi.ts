import type { MemberResponse } from '~/types/member'
import type { OrganizationResponse } from '~/types/organization'
import type { SlugAvailabilityResponse, SlugResolveResponse } from '~/types/slug'

interface OrganizationSummaryResponse {
  id: string
  /** 組織スラッグ（URLルーティング用）。{@code /organizations/{slug}} に使用する。 */
  slug: string
  name: string
  nickname1: string | null
  iconUrl: string | null
  prefecture: string | null
  city: string | null
  orgType: string
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
  /** チームスラッグ（URLルーティング用）。BE `OrgTeamSummaryResponse.slug` と 1:1。 */
  slug: string
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

export function useOrganizationApi() {
  const api = useApi()
  const { handleApiError } = useErrorHandler()

  // === CRUD ===
  async function getOrganization(orgSlug: string) {
    return api<{ data: OrganizationResponse }>(`/api/v1/organizations/${orgSlug}`)
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

  /**
   * 組織作成時の slug 可用性をチェックする（BE #1538）。
   *
   * `GET /api/v1/organizations/slug-available?slug=xxx` を叩く。
   * 形式不正・予約語・重複・未指定のいずれでも BE は常に 200 を返し、
   * `available=false` のとき `reason` に理由コードが入る。
   */
  async function checkOrganizationSlugAvailable(
    slug: string,
  ): Promise<SlugAvailabilityResponse> {
    const query = new URLSearchParams({ slug })
    const res = await api<{ data: SlugAvailabilityResponse }>(`/api/v1/organizations/slug-available?${query}`)
    return res.data
  }

  /**
   * 組織 slug をリネームする（BE #1542）。
   *
   * `PUT /api/v1/organizations/{currentSlug}/slug` を body `{ newSlug }` で叩く。
   * 認可は BE 側で ADMIN/DEPUTY 相当に限定される。
   *
   * - 200: 成功（`data.slug` に新 slug。`newSlug==現slug` なら no-op 200）
   * - 422: 形式不正 / 予約語
   * - 409: 重複（SLUG_ALREADY_TAKEN）/ 履歴予約（SLUG_RETIRED）
   *
   * 旧 slug は 301 解決用に履歴予約されるため、成功後は新 slug の URL へ遷移すること。
   */
  async function renameOrganizationSlug(currentSlug: string, newSlug: string) {
    return api<{ data: OrganizationResponse }>(`/api/v1/organizations/${currentSlug}/slug`, {
      method: 'PUT',
      body: { newSlug },
    })
  }

  /**
   * 組織 slug を解決する（旧 slug → 新 slug の 301 判定・BE #1542）。
   *
   * `GET /api/v1/public/organizations/slug-resolve?slug=xxx` を叩く（permitAll・レート制限）。
   * 名前など実データは返さず status / canonicalSlug のみ。
   */
  async function resolveOrganizationSlug(slug: string): Promise<SlugResolveResponse> {
    const query = new URLSearchParams({ slug })
    return api<SlugResolveResponse>(`/api/v1/public/organizations/slug-resolve?${query}`)
  }

  async function updateOrganization(orgSlug: string, body: Record<string, unknown>) {
    return api<{ data: OrganizationResponse }>(`/api/v1/organizations/${orgSlug}`, {
      method: 'PATCH',
      body,
    })
  }

  async function deleteOrganization(orgSlug: string) {
    return api(`/api/v1/organizations/${orgSlug}`, { method: 'DELETE' })
  }

  // === メンバー管理 ===
  async function getMembers(orgSlug: string, params?: { page?: number; size?: number }) {
    const query = new URLSearchParams()
    query.set('page', String(params?.page ?? 0))
    query.set('size', String(params?.size ?? 20))
    return api<PagedData<MemberResponse>>(`/api/v1/organizations/${orgSlug}/members?${query}`)
  }

  async function changeRole(orgSlug: string, userId: number, roleId: number) {
    return api(`/api/v1/organizations/${orgSlug}/members/${userId}/role`, {
      method: 'PATCH',
      body: { roleId },
    })
  }

  async function removeMember(orgSlug: string, userId: number) {
    return api(`/api/v1/organizations/${orgSlug}/members/${userId}`, { method: 'DELETE' })
  }

  async function leaveOrganization(orgSlug: string) {
    return api(`/api/v1/organizations/${orgSlug}/me`, { method: 'DELETE' })
  }

  // === 招待トークン ===
  async function createInviteToken(
    orgSlug: string,
    body: { roleId: number; expiresIn: string | null; maxUses: number | null },
  ) {
    return api<{ data: InviteTokenResponse }>(`/api/v1/organizations/${orgSlug}/invite-tokens`, {
      method: 'POST',
      body,
    })
  }

  async function getInviteTokens(orgSlug: string) {
    return api<{ data: InviteTokenResponse[] }>(`/api/v1/organizations/${orgSlug}/invite-tokens`)
  }

  async function deleteInviteToken(orgSlug: string, tokenId: number) {
    return api(`/api/v1/organizations/${orgSlug}/invite-tokens/${tokenId}`, { method: 'DELETE' })
  }

  // === アーカイブ ===
  async function archiveOrganization(orgSlug: string) {
    return api(`/api/v1/organizations/${orgSlug}/archive`, { method: 'PATCH' })
  }

  async function unarchiveOrganization(orgSlug: string) {
    return api(`/api/v1/organizations/${orgSlug}/unarchive`, { method: 'PATCH' })
  }

  async function restoreOrganization(orgSlug: string) {
    return api(`/api/v1/organizations/${orgSlug}/restore`, { method: 'PATCH' })
  }

  // === 全メンバー一覧 ===
  async function getAllMembers(orgSlug: string) {
    return api<{ data: MemberResponse[] }>(`/api/v1/organizations/${orgSlug}/members/all`)
  }

  // === フォロー（SUPPORTER） ===
  async function followOrganization(orgSlug: string) {
    return api(`/api/v1/organizations/${orgSlug}/follow`, { method: 'POST' })
  }

  async function unfollowOrganization(orgSlug: string) {
    return api(`/api/v1/organizations/${orgSlug}/follow`, { method: 'DELETE' })
  }

  async function getFollowStatus(orgSlug: string) {
    return api<{ data: FollowStatusResponse }>(`/api/v1/organizations/${orgSlug}/follow/status`)
  }

  // === サポーター管理（管理者） ===
  async function getSupporters(orgSlug: string, params?: { page?: number; size?: number }) {
    const query = new URLSearchParams()
    query.set('page', String(params?.page ?? 0))
    query.set('size', String(params?.size ?? 50))
    return api<PagedData<SupporterResponse>>(`/api/v1/organizations/${orgSlug}/supporters?${query}`)
  }

  async function getSupporterApplications(
    orgSlug: string,
    params?: { page?: number; size?: number },
  ) {
    const query = new URLSearchParams()
    query.set('page', String(params?.page ?? 0))
    query.set('size', String(params?.size ?? 50))
    return api<PagedData<SupporterApplicationResponse>>(
      `/api/v1/organizations/${orgSlug}/supporter-applications?${query}`,
    )
  }

  async function approveSupporterApplication(orgSlug: string, applicationId: number) {
    return api(`/api/v1/organizations/${orgSlug}/supporter-applications/${applicationId}/approve`, {
      method: 'POST',
    })
  }

  async function rejectSupporterApplication(orgSlug: string, applicationId: number) {
    return api(`/api/v1/organizations/${orgSlug}/supporter-applications/${applicationId}/reject`, {
      method: 'POST',
    })
  }

  async function bulkApproveSupporterApplications(orgSlug: string, applicationIds: number[]) {
    return api(`/api/v1/organizations/${orgSlug}/supporter-applications/bulk-approve`, {
      method: 'POST',
      body: { applicationIds },
    })
  }

  async function getSupporterSettings(orgSlug: string) {
    return api<{ data: SupporterSettings }>(`/api/v1/organizations/${orgSlug}/supporter-settings`)
  }

  async function updateSupporterSettings(orgSlug: string, body: Partial<SupporterSettings>) {
    return api<{ data: SupporterSettings }>(`/api/v1/organizations/${orgSlug}/supporter-settings`, {
      method: 'PUT',
      body,
    })
  }

  // === 権限グループ管理 ===
  async function getPermissionGroups(orgSlug: string) {
    return api<{ data: PermissionGroupResponse[] }>(
      `/api/v1/organizations/${orgSlug}/permission-groups`,
    )
  }

  async function createPermissionGroup(
    orgSlug: string,
    body: { name: string; description?: string; permissions: string[] },
  ) {
    return api<{ data: PermissionGroupResponse }>(
      `/api/v1/organizations/${orgSlug}/permission-groups`,
      { method: 'POST', body },
    )
  }

  async function updatePermissionGroup(
    orgSlug: string,
    groupId: number,
    body: { name?: string; description?: string; permissions?: string[] },
  ) {
    return api<{ data: PermissionGroupResponse }>(
      `/api/v1/organizations/${orgSlug}/permission-groups/${groupId}`,
      { method: 'PATCH', body },
    )
  }

  async function deletePermissionGroup(orgSlug: string, groupId: number) {
    return api(`/api/v1/organizations/${orgSlug}/permission-groups/${groupId}`, { method: 'DELETE' })
  }

  async function assignPermissionGroups(orgSlug: string, userId: number, groupIds: number[]) {
    return api(`/api/v1/organizations/${orgSlug}/members/${userId}/permission-groups`, {
      method: 'PUT',
      body: { groupIds },
    })
  }

  // === ブロック管理 ===
  async function getBlocks(orgSlug: string) {
    return api<{ data: BlockResponse[] }>(`/api/v1/organizations/${orgSlug}/blocks`)
  }

  async function createBlock(orgSlug: string, body: { userId: number; reason?: string }) {
    return api<{ data: BlockResponse }>(`/api/v1/organizations/${orgSlug}/blocks`, {
      method: 'POST',
      body,
    })
  }

  async function removeBlock(orgSlug: string, blockId: number) {
    return api(`/api/v1/organizations/${orgSlug}/blocks/${blockId}`, { method: 'DELETE' })
  }

  // === オーナー移譲 ===
  /**
   * オーナー（ADMIN）を別メンバーへ譲渡する。
   *
   * BE 契約は `POST /api/v1/organizations/{slug}/transfer-ownership?targetUserId={id}` であり、
   * 譲渡先はリクエストボディではなく**クエリパラメータ `targetUserId`** で渡す
   * （`OrganizationController#transferOwnership` の `@RequestParam Long targetUserId` / `docs/openapi.json`）。
   * 以前はボディ `{ newAdminUserId }` を送っており実契約と不一致だった（CMP-051 で是正）。
   */
  async function transferOwnership(orgSlug: string, targetUserId: number) {
    const query = new URLSearchParams({ targetUserId: String(targetUserId) })
    return api(`/api/v1/organizations/${orgSlug}/transfer-ownership?${query}`, { method: 'POST' })
  }

  // === アクセス要件 ===
  async function getAccessRequirements(orgSlug: string) {
    return api<{ data: Record<string, unknown> }>(
      `/api/v1/organizations/${orgSlug}/access-requirements`,
    )
  }

  async function updateAccessRequirements(orgSlug: string, body: Record<string, unknown>) {
    return api<{ data: Record<string, unknown> }>(
      `/api/v1/organizations/${orgSlug}/access-requirements`,
      { method: 'PUT', body },
    )
  }

  // === コンテンツ有料化設定 ===
  async function getContentPaymentGates(orgSlug: string) {
    return api<{
      data: Record<string, unknown>[]
      meta: { page: number; size: number; totalElements: number; totalPages: number }
    }>(`/api/v1/organizations/${orgSlug}/content-payment-gates`)
  }

  async function updateContentPaymentGates(orgSlug: string, body: Record<string, unknown>) {
    return api(`/api/v1/organizations/${orgSlug}/content-payment-gates`, { method: 'PUT', body })
  }

  // === 組織内チーム一覧 ===
  async function getTeamsInOrg(orgSlug: string) {
    return api<{ data: TeamSummaryResponse[] }>(`/api/v1/organizations/${orgSlug}/teams`)
  }

  return {
    getOrganization,
    searchOrganizations,
    createOrganization,
    checkOrganizationSlugAvailable,
    renameOrganizationSlug,
    resolveOrganizationSlug,
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
    getFollowStatus,
    getSupporters,
    getSupporterApplications,
    approveSupporterApplication,
    rejectSupporterApplication,
    bulkApproveSupporterApplications,
    getSupporterSettings,
    updateSupporterSettings,
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
