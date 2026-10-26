/**
 * 柱②-2 販促プロビジョニング: SYSTEM_ADMIN 向け API クライアント。
 *
 * バックエンド: {@code /api/v1/system-admin/provisioning} 配下（ROLE_SYSTEM_ADMIN のみ）。
 * 金型: {@code useIncidentBannerAdmin.ts}。
 */
import type { components } from '~/types/generated/index'

export type ProvisioningOrganizationCreateRequest =
  components['schemas']['ProvisioningOrganizationCreateRequest']
export type ProvisioningTeamCreateRequest =
  components['schemas']['ProvisioningTeamCreateRequest']
export type ProvisioningInvitationResponse =
  components['schemas']['ProvisioningInvitationResponse']

const BASE = '/api/v1/system-admin/provisioning'

export function useProvisioningAdminApi() {
  const api = useApi()

  /** 組織を PROVISIONED 状態で事前作成し、ADMIN 招待を送る。 */
  async function createOrganization(
    req: ProvisioningOrganizationCreateRequest,
  ): Promise<ProvisioningInvitationResponse> {
    return api<ProvisioningInvitationResponse>(`${BASE}/organizations`, {
      method: 'POST',
      body: req,
    })
  }

  /** チームを PROVISIONED 状態で事前作成し、ADMIN 招待を送る。 */
  async function createTeam(
    req: ProvisioningTeamCreateRequest,
  ): Promise<ProvisioningInvitationResponse> {
    return api<ProvisioningInvitationResponse>(`${BASE}/teams`, {
      method: 'POST',
      body: req,
    })
  }

  /** プロビジョニング招待の一覧を取得する。 */
  async function list(): Promise<ProvisioningInvitationResponse[]> {
    return api<ProvisioningInvitationResponse[]>(`${BASE}/invitations`)
  }

  /** 招待を再送する（旧トークンは失効し、新しいトークンを発行する）。 */
  async function resend(invitationId: string): Promise<ProvisioningInvitationResponse> {
    return api<ProvisioningInvitationResponse>(
      `${BASE}/invitations/${encodeURIComponent(invitationId)}/resend`,
      { method: 'POST' },
    )
  }

  /** 招待を取消す。 */
  async function cancel(invitationId: string): Promise<void> {
    await api(`${BASE}/invitations/${encodeURIComponent(invitationId)}/cancel`, {
      method: 'POST',
    })
  }

  return {
    createOrganization,
    createTeam,
    list,
    resend,
    cancel,
  }
}
