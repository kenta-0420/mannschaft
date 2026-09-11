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

  /**
   * チームの全メンバーを取得する（CMP-260910-1555）。
   *
   * `getMembers` は先頭 1 ページしか返さないため、`meta.totalPages` を無視すると
   * ページサイズを超える人数のチームで後半のメンバーが画面に現れない。
   * 時給設定のように「全員が漏れなく対象に入る」ことが要件の画面ではこれが直接の欠陥になる
   * （設定されなかったメンバーはシフト公開のたびに予算消化がスキップされ続ける）。
   *
   * なお `pageSize` の既定値 100 は BE の `spring.data.web.pageable.max-page-size`（= 100）に
   * 合わせてある。これを超える値を送っても BE 側で 100 に丸められるだけで、
   * 「200 件ずつ取っているつもりが実際は 100 件ずつ」という取り違えを生むため、実値に揃える。
   *
   * @param teamSlug チームの slug
   * @param pageSize 1 ページあたりの取得件数（既定 100 = BE の上限）
   * @returns 全ページを連結したメンバー一覧
   */
  async function getAllMembers(teamSlug: string, pageSize = 100): Promise<MemberResponse[]> {
    const first = await getMembers(teamSlug, { page: 0, size: pageSize })
    const totalPages = first.meta?.totalPages ?? 1
    if (totalPages <= 1) return first.data

    const rest = await Promise.all(
      Array.from({ length: totalPages - 1 }, (_, i) =>
        getMembers(teamSlug, { page: i + 1, size: pageSize }),
      ),
    )
    return [...first.data, ...rest.flatMap(res => res.data)]
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
    getAllMembers,
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
