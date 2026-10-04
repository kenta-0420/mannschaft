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
 */
import type { components } from '~/types/generated'

type Schemas = components['schemas']

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

  return { getSettings, updateSettings, getApplicationForm, canApply, applyToOrganization }
}
