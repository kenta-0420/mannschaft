import type { MemberResponse } from '~/types/member'

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

/**
 * チームメンバー管理・招待トークン・権限グループを扱うサブ composable。
 *
 * useTeamApi を分割した責務マップのうち「メンバー / 招待 / 権限グループ」を担当する。
 * 公開関数のシグネチャは元の useTeamApi と同一を維持している。
 */
export function useTeamMembers() {
  const api = useApi()

  // === メンバー管理 ===
  async function getMembers(teamSlug: string, params?: { page?: number; size?: number }) {
    const query = new URLSearchParams()
    query.set('page', String(params?.page ?? 0))
    query.set('size', String(params?.size ?? 20))
    return api<PagedData<MemberResponse>>(`/api/v1/teams/${teamSlug}/members?${query}`)
  }

  async function changeRole(teamSlug: string, userId: number, roleId: number) {
    return api(`/api/v1/teams/${teamSlug}/members/${userId}/role`, {
      method: 'PATCH',
      body: { roleId },
    })
  }

  async function removeMember(teamSlug: string, userId: number) {
    return api(`/api/v1/teams/${teamSlug}/members/${userId}`, { method: 'DELETE' })
  }

  async function leaveTeam(teamSlug: string) {
    return api(`/api/v1/teams/${teamSlug}/me`, { method: 'DELETE' })
  }

  // === 招待トークン ===
  async function createInviteToken(
    teamSlug: string,
    body: { roleId: number; expiresIn: string | null; maxUses: number | null },
  ) {
    return api<{ data: InviteTokenResponse }>(`/api/v1/teams/${teamSlug}/invite-tokens`, {
      method: 'POST',
      body,
    })
  }

  async function getInviteTokens(teamSlug: string) {
    return api<{ data: InviteTokenResponse[] }>(`/api/v1/teams/${teamSlug}/invite-tokens`)
  }

  async function deleteInviteToken(teamSlug: string, tokenId: number) {
    return api(`/api/v1/teams/${teamSlug}/invite-tokens/${tokenId}`, { method: 'DELETE' })
  }

  // === 権限グループ管理 ===
  async function getPermissionGroups(teamSlug: string) {
    return api<{
      data: Array<{
        id: number
        name: string
        description: string | null
        permissions: string[]
        createdAt: string
      }>
    }>(`/api/v1/teams/${teamSlug}/permission-groups`)
  }

  async function createPermissionGroup(
    teamSlug: string,
    body: { name: string; description?: string; permissions: string[] },
  ) {
    return api(`/api/v1/teams/${teamSlug}/permission-groups`, { method: 'POST', body })
  }

  async function updatePermissionGroup(
    teamSlug: string,
    groupId: number,
    body: { name?: string; description?: string; permissions?: string[] },
  ) {
    return api(`/api/v1/teams/${teamSlug}/permission-groups/${groupId}`, { method: 'PATCH', body })
  }

  async function deletePermissionGroup(teamSlug: string, groupId: number) {
    return api(`/api/v1/teams/${teamSlug}/permission-groups/${groupId}`, { method: 'DELETE' })
  }

  async function assignPermissionGroups(teamSlug: string, userId: number, groupIds: number[]) {
    return api(`/api/v1/teams/${teamSlug}/members/${userId}/permission-groups`, {
      method: 'PUT',
      body: { groupIds },
    })
  }

  return {
    getMembers,
    changeRole,
    removeMember,
    leaveTeam,
    createInviteToken,
    getInviteTokens,
    deleteInviteToken,
    getPermissionGroups,
    createPermissionGroup,
    updatePermissionGroup,
    deletePermissionGroup,
    assignPermissionGroups,
  }
}
