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
 * - GET    /api/v1/organizations/{slug}/team-groups                             … グループ一覧（機能 off は 409）
 */
import type { components } from '~/types/generated'
import { getLoadErrorHttpStatus } from '~/utils/loadError'

type Schemas = components['schemas']

export type TeamOrgAffiliation = Schemas['TeamOrgAffiliationResponse']
export type TeamOrgAffiliationRestriction = Schemas['TeamOrgAffiliationRestrictionResponse']
export type OrgTeamGroup = Schemas['OrgTeamGroupResponse']
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
   * チームグループ一覧。グループ機能が off の組織は BE が 409（ORG_067）を返すため、
   * それだけを「グループなし」（null）として扱う。それ以外の失敗は呼び出し側へ投げる。
   */
  async function listTeamGroups(orgSlug: string): Promise<OrgTeamGroup[] | null> {
    try {
      const res = await api<{ data?: OrgTeamGroup[] }>(`${base(orgSlug)}/team-groups`)
      return res.data ?? []
    }
    catch (e) {
      if (getLoadErrorHttpStatus(e) === 409) return null
      throw e
    }
  }

  return {
    listApplications,
    approveApplication,
    rejectApplication,
    listInvites,
    inviteTeam,
    cancelInvite,
    listRestrictions,
    liftRestriction,
    listTeamGroups,
  }
}
