/**
 * F01.2.1 8-B — 組織側の加盟管理（申請・招待・制限）API クライアント。
 *
 * 型は生成型（`types/generated`）を正とする。いずれも組織 ADMIN 専用（BE が 403 を返す）。
 *
 * - GET    /api/v1/organizations/{slug}/team-applications                       … 受信した加盟申請（PENDING）
 * - POST   /api/v1/organizations/{slug}/team-applications/{membershipId}/approve … 承認（グループ確定）
 * - POST   /api/v1/organizations/{slug}/team-applications/{membershipId}/reject  … 拒否（冷却 or ブロック）
 * - GET    /api/v1/organizations/{slug}/team-invites                            … 送信済み招待
 * - POST   /api/v1/organizations/{slug}/team-invites                            … チームを招待
 * - DELETE /api/v1/organizations/{slug}/team-invites/{teamSlug}                 … 招待の取消
 * - GET    /api/v1/organizations/{slug}/team-affiliation-restrictions           … 組織が止めている申請
 * - DELETE /api/v1/organizations/{slug}/team-affiliation-restrictions/{id}      … 制限の解除
 * - DELETE /api/v1/organizations/{slug}/teams/{teamSlug}                        … 成立済みチームの除名
 * - GET    /api/v1/organizations/{slug}/team-groups                             … グループ一覧（機能 off は 409）
 * - POST   /api/v1/organizations/{slug}/team-groups                             … グループ作成（8-C）
 * - PATCH  /api/v1/organizations/{slug}/team-groups/{groupId}                   … 改名・説明変更（8-C）
 * - DELETE /api/v1/organizations/{slug}/team-groups/{groupId}                   … 削除（所属チームは未分類へ。8-C）
 * - PUT    /api/v1/organizations/{slug}/team-groups/order                       … 並び替え（8-C）
 * （加盟チーム一覧 GET /organizations/{slug}/teams は親シェルの orgTeams が teamGroup 付きで持つ。絞り込みはその一覧を FE で行う）
 * - PUT    /api/v1/organizations/{slug}/teams/{teamSlug}/team-group             … 単体割当（groupId=null で未分類）
 * - PUT    /api/v1/organizations/{slug}/team-group-assignments                  … 一括割当
 */
import type { components } from '~/types/generated'
import { getLoadErrorHttpStatus } from '~/utils/loadError'

type Schemas = components['schemas']

export type TeamOrgAffiliation = Schemas['TeamOrgAffiliationResponse']
export type TeamOrgAffiliationRestriction = Schemas['TeamOrgAffiliationRestrictionResponse']
export type OrgTeamGroup = Schemas['OrgTeamGroupResponse']
export type CreateOrgTeamGroupRequest = Schemas['CreateOrgTeamGroupRequest']
export type UpdateOrgTeamGroupRequest = Schemas['UpdateOrgTeamGroupRequest']

/** グループ一覧と、その meta（未分類のチーム数・グループ数の上限）。 */
export interface OrgTeamGroupList {
  groups: OrgTeamGroup[]
  unassignedTeamCount: number
  limit: number
}
export type ApproveTeamApplicationRequest = Schemas['ApproveTeamApplicationRequest']
export type RejectTeamApplicationRequest = Schemas['RejectTeamApplicationRequest']
export type InviteTeamToOrganizationRequest = Schemas['InviteTeamToOrganizationRequest']

export interface PageResult<T> {
  items: T[]
  total: number
  totalPages: number
}

interface PagedBody<T> {
  data?: T[]
  meta?: { total?: number, totalPages?: number }
}

function toPageResult<T>(body: PagedBody<T>): PageResult<T> {
  const items = body.data ?? []
  return {
    items,
    total: body.meta?.total ?? items.length,
    totalPages: body.meta?.totalPages ?? 1,
  }
}

export function useOrgTeamMembershipApi() {
  const api = useApi()

  const base = (orgSlug: string) => `/api/v1/organizations/${encodeURIComponent(orgSlug)}`

  /** 成立済みの加盟だけを解除する。204（body なし）。グループ機能には依存しない。 */
  async function removeTeam(orgSlug: string, teamSlug: string): Promise<void> {
    await api(`${base(orgSlug)}/teams/${encodeURIComponent(teamSlug)}`, { method: 'DELETE' })
  }

  function pageQuery(page: number, size: number): string {
    return `page=${page}&size=${size}`
  }

  async function listApplications(orgSlug: string, page = 0, size = 20): Promise<PageResult<TeamOrgAffiliation>> {
    return toPageResult(
      await api<PagedBody<TeamOrgAffiliation>>(`${base(orgSlug)}/team-applications?${pageQuery(page, size)}`),
    )
  }

  async function approveApplication(
    orgSlug: string,
    membershipId: number,
    body: ApproveTeamApplicationRequest,
  ): Promise<void> {
    await api(`${base(orgSlug)}/team-applications/${membershipId}/approve`, { method: 'POST', body })
  }

  async function rejectApplication(
    orgSlug: string,
    membershipId: number,
    body: RejectTeamApplicationRequest,
  ): Promise<void> {
    await api(`${base(orgSlug)}/team-applications/${membershipId}/reject`, { method: 'POST', body })
  }

  async function listInvites(orgSlug: string, page = 0, size = 20): Promise<PageResult<TeamOrgAffiliation>> {
    return toPageResult(
      await api<PagedBody<TeamOrgAffiliation>>(`${base(orgSlug)}/team-invites?${pageQuery(page, size)}`),
    )
  }

  async function inviteTeam(orgSlug: string, body: InviteTeamToOrganizationRequest): Promise<void> {
    await api(`${base(orgSlug)}/team-invites`, { method: 'POST', body })
  }

  async function cancelInvite(orgSlug: string, teamSlug: string): Promise<void> {
    await api(`${base(orgSlug)}/team-invites/${encodeURIComponent(teamSlug)}`, { method: 'DELETE' })
  }

  async function listRestrictions(
    orgSlug: string,
    page = 0,
    size = 20,
  ): Promise<PageResult<TeamOrgAffiliationRestriction>> {
    return toPageResult(
      await api<PagedBody<TeamOrgAffiliationRestriction>>(
        `${base(orgSlug)}/team-affiliation-restrictions?${pageQuery(page, size)}`,
      ),
    )
  }

  async function liftRestriction(orgSlug: string, restrictionId: string): Promise<void> {
    await api(`${base(orgSlug)}/team-affiliation-restrictions/${encodeURIComponent(restrictionId)}`, {
      method: 'DELETE',
    })
  }

  /**
   * チームグループ一覧（meta つき）。グループ機能が off の組織は BE が 409（ORG_067）を返すため、
   * それだけを「機能 off」（null）として扱う。それ以外の失敗は呼び出し側へ投げる。
   */
  async function listTeamGroupList(orgSlug: string): Promise<OrgTeamGroupList | null> {
    try {
      const res = await api<{ data?: OrgTeamGroup[], meta?: { unassignedTeamCount?: number, limit?: number } }>(
        `${base(orgSlug)}/team-groups`,
      )
      return {
        groups: res.data ?? [],
        unassignedTeamCount: res.meta?.unassignedTeamCount ?? 0,
        limit: res.meta?.limit ?? 100,
      }
    }
    catch (e) {
      if (getLoadErrorHttpStatus(e) === 409) return null
      throw e
    }
  }

  /** グループ一覧のみ。機能 off は null。 */
  async function listTeamGroups(orgSlug: string): Promise<OrgTeamGroup[] | null> {
    return (await listTeamGroupList(orgSlug))?.groups ?? null
  }

  async function createTeamGroup(orgSlug: string, body: CreateOrgTeamGroupRequest): Promise<OrgTeamGroup | undefined> {
    const res = await api<{ data?: OrgTeamGroup }>(`${base(orgSlug)}/team-groups`, { method: 'POST', body })
    return res.data
  }

  async function updateTeamGroup(
    orgSlug: string,
    groupId: string,
    body: UpdateOrgTeamGroupRequest,
  ): Promise<OrgTeamGroup | undefined> {
    const res = await api<{ data?: OrgTeamGroup }>(
      `${base(orgSlug)}/team-groups/${encodeURIComponent(groupId)}`,
      { method: 'PATCH', body },
    )
    return res.data
  }

  async function deleteTeamGroup(orgSlug: string, groupId: string): Promise<void> {
    await api(`${base(orgSlug)}/team-groups/${encodeURIComponent(groupId)}`, { method: 'DELETE' })
  }

  /** 生存グループ全件の ID を新しい順で送る。 */
  async function reorderTeamGroups(orgSlug: string, groupIds: string[]): Promise<OrgTeamGroup[]> {
    const res = await api<{ data?: OrgTeamGroup[] }>(`${base(orgSlug)}/team-groups/order`, {
      method: 'PUT',
      body: { groupIds },
    })
    return res.data ?? []
  }

  /** 1 チームを割り当てる。groupId=null で未分類へ戻す。 */
  async function assignTeamGroup(orgSlug: string, teamSlug: string, groupId: string | null): Promise<void> {
    await api(`${base(orgSlug)}/teams/${encodeURIComponent(teamSlug)}/team-group`, {
      method: 'PUT',
      body: { groupId },
    })
  }

  /** 複数チームを一括で割り当てる（全部か無しか。1 回 500 件まで）。 */
  async function bulkAssignTeamGroup(
    orgSlug: string,
    groupId: string | null,
    teamSlugs: string[],
  ): Promise<{ updatedCount: number }> {
    const res = await api<{ data?: { updatedCount?: number } }>(`${base(orgSlug)}/team-group-assignments`, {
      method: 'PUT',
      body: { groupId, teamSlugs },
    })
    return { updatedCount: res.data?.updatedCount ?? 0 }
  }

  return {
    removeTeam,
    listApplications,
    approveApplication,
    rejectApplication,
    listInvites,
    inviteTeam,
    cancelInvite,
    listRestrictions,
    liftRestriction,
    listTeamGroups,
    listTeamGroupList,
    createTeamGroup,
    updateTeamGroup,
    deleteTeamGroup,
    reorderTeamGroups,
    assignTeamGroup,
    bulkAssignTeamGroup,
  }
}
