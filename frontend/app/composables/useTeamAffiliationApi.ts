/**
 * F01.2.1 8-A — チーム加盟（組織側の設定・チーム側の申請）API クライアント。
 *
 * 型は生成型（`types/generated`）を正とする。レスポンスは ApiResponse でラップされているため `.data` を剥がして返す。
 *
 * - GET/PUT /api/v1/organizations/{slug}/team-affiliation-settings        … 組織 ADMIN の設定
 * - GET     /api/v1/organizations/{slug}/team-application-form            … 申請ダイアログの内容
 * - GET     /api/v1/me/org-affiliation-eligibility?organizationSlug=      … 申請ボタンを出すか（常に 200）
 * - POST    /api/v1/teams/{teamSlug}/org-applications                     … 組織へ加盟申請
 *
 * 権限で出し分けるボタンは FE で権限を推測せず、eligibility の `canApply` に従う。
 *
 * F01.2.1 8-D — チーム側の加盟管理（所属組織・受信招待・申請中・招待の制限・離脱）。
 * いずれもチームの加盟操作者（ADMIN、または MANAGE_ORG_AFFILIATION を付与された者）専用で、それ以外は BE が 403 を返す。
 *
 * - GET    /api/v1/teams/{teamSlug}/organizations                          … 所属組織（ACTIVE）
 * - GET    /api/v1/teams/{teamSlug}/org-invites                            … 受信した招待
 * - POST   /api/v1/teams/{teamSlug}/org-invites/{membershipId}/accept      … 招待の承諾
 * - POST   /api/v1/teams/{teamSlug}/org-invites/{membershipId}/reject      … 招待の辞退（block で今後の招待を止める）
 * - GET    /api/v1/teams/{teamSlug}/org-applications                       … 申請中
 * - DELETE /api/v1/teams/{teamSlug}/org-applications/{membershipId}        … 申請の取下げ
 * - GET    /api/v1/teams/{teamSlug}/org-affiliation-restrictions           … チームが止めている招待
 * - DELETE /api/v1/teams/{teamSlug}/org-affiliation-restrictions/{id}      … 制限の解除
 * - DELETE /api/v1/teams/{teamSlug}/organizations/{orgSlug}                … 離脱
 */
import type { components } from '~/types/generated'
import type { PageResult } from '~/composables/useOrgTeamMembershipApi'

type Schemas = components['schemas']

// 型名は useOrgTeamMembershipApi と重複するため、ここでは export しない（auto-import の重複警告を避ける）。
type TeamOrgAffiliation = Schemas['TeamOrgAffiliationResponse']
type TeamOrgAffiliationRestriction = Schemas['TeamOrgAffiliationRestrictionResponse']
export type TeamOrgSummary = Schemas['TeamOrgSummaryResponse']

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

export type TeamAffiliationSettings = Schemas['TeamAffiliationSettingsResponse']
export type UpdateTeamAffiliationSettingsRequest = Schemas['UpdateTeamAffiliationSettingsRequest']
export type TeamApplicationForm = Schemas['TeamApplicationFormResponse']
export type ApplyToOrganizationRequest = Schemas['ApplyToOrganizationRequest']
export type ApplicationGroupMode = 'OFF' | 'OPTIONAL' | 'REQUIRED'

export function useTeamAffiliationApi() {
  const api = useApi()

  async function getSettings(orgSlug: string): Promise<TeamAffiliationSettings> {
    const res = await api<{ data: TeamAffiliationSettings }>(
      `/api/v1/organizations/${encodeURIComponent(orgSlug)}/team-affiliation-settings`,
    )
    return res.data
  }

  async function updateSettings(
    orgSlug: string,
    body: UpdateTeamAffiliationSettingsRequest,
  ): Promise<TeamAffiliationSettings> {
    const res = await api<{ data: TeamAffiliationSettings }>(
      `/api/v1/organizations/${encodeURIComponent(orgSlug)}/team-affiliation-settings`,
      { method: 'PUT', body },
    )
    return res.data
  }

  async function getApplicationForm(orgSlug: string): Promise<TeamApplicationForm> {
    const res = await api<{ data: TeamApplicationForm }>(
      `/api/v1/organizations/${encodeURIComponent(orgSlug)}/team-application-form`,
    )
    return res.data
  }

  /** 申請ボタンを出してよいか。理由は区別されない（受付 off・権限なし・見えない組織はすべて false）。 */
  async function canApply(orgSlug: string): Promise<boolean> {
    const res = await api<{ data: Schemas['OrgAffiliationEligibilityResponse'] }>(
      `/api/v1/me/org-affiliation-eligibility?organizationSlug=${encodeURIComponent(orgSlug)}`,
    )
    return res.data?.canApply === true
  }

  async function applyToOrganization(
    teamSlug: string,
    body: ApplyToOrganizationRequest,
  ): Promise<void> {
    await api(`/api/v1/teams/${encodeURIComponent(teamSlug)}/org-applications`, {
      method: 'POST',
      body,
    })
  }

  // === F01.2.1 8-D チーム側の加盟管理 ===

  const teamBase = (teamSlug: string) => `/api/v1/teams/${encodeURIComponent(teamSlug)}`
  const pageQuery = (page: number, size: number) => `page=${page}&size=${size}`

  async function listOrganizations(teamSlug: string): Promise<TeamOrgSummary[]> {
    const res = await api<{ data?: TeamOrgSummary[] }>(`${teamBase(teamSlug)}/organizations`)
    return res.data ?? []
  }

  async function listInvites(teamSlug: string, page = 0, size = 20): Promise<PageResult<TeamOrgAffiliation>> {
    return toPageResult(
      await api<PagedBody<TeamOrgAffiliation>>(`${teamBase(teamSlug)}/org-invites?${pageQuery(page, size)}`),
    )
  }

  async function acceptInvite(teamSlug: string, membershipId: number): Promise<void> {
    await api(`${teamBase(teamSlug)}/org-invites/${membershipId}/accept`, { method: 'POST' })
  }

  async function declineInvite(teamSlug: string, membershipId: number, block: boolean): Promise<void> {
    await api(`${teamBase(teamSlug)}/org-invites/${membershipId}/reject`, { method: 'POST', body: { block } })
  }

  async function listApplications(teamSlug: string, page = 0, size = 20): Promise<PageResult<TeamOrgAffiliation>> {
    return toPageResult(
      await api<PagedBody<TeamOrgAffiliation>>(`${teamBase(teamSlug)}/org-applications?${pageQuery(page, size)}`),
    )
  }

  async function withdrawApplication(teamSlug: string, membershipId: number): Promise<void> {
    await api(`${teamBase(teamSlug)}/org-applications/${membershipId}`, { method: 'DELETE' })
  }

  async function listRestrictions(
    teamSlug: string,
    page = 0,
    size = 20,
  ): Promise<PageResult<TeamOrgAffiliationRestriction>> {
    return toPageResult(
      await api<PagedBody<TeamOrgAffiliationRestriction>>(
        `${teamBase(teamSlug)}/org-affiliation-restrictions?${pageQuery(page, size)}`,
      ),
    )
  }

  async function liftRestriction(teamSlug: string, restrictionId: string): Promise<void> {
    await api(`${teamBase(teamSlug)}/org-affiliation-restrictions/${encodeURIComponent(restrictionId)}`, {
      method: 'DELETE',
    })
  }

  async function leaveOrganization(teamSlug: string, orgSlug: string): Promise<void> {
    await api(`${teamBase(teamSlug)}/organizations/${encodeURIComponent(orgSlug)}`, { method: 'DELETE' })
  }

  return {
    getSettings,
    updateSettings,
    getApplicationForm,
    canApply,
    applyToOrganization,
    listOrganizations,
    listInvites,
    acceptInvite,
    declineInvite,
    listApplications,
    withdrawApplication,
    listRestrictions,
    liftRestriction,
    leaveOrganization,
  }
}
