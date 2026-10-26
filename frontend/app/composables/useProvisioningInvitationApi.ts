/**
 * 柱②-2 販促プロビジョニング: 招待の下見/承諾 API クライアント（承諾者側・要ログイン）。
 *
 * バックエンド: {@code /api/v1/provisioning/invitations} 配下。
 * トークンは URL パスへは載せず、POST ボディで渡す（BE 確定契約）。
 */
import type { components } from '~/types/generated/index'

export type ProvisioningInvitationPreviewResponse =
  components['schemas']['ProvisioningInvitationPreviewResponse']
export type ProvisioningInvitationAcceptResponse =
  components['schemas']['ProvisioningInvitationAcceptResponse']

const BASE = '/api/v1/provisioning/invitations'

export function useProvisioningInvitationApi() {
  const api = useApi()

  /** 招待トークンの下見（承諾前確認画面用）。 */
  async function preview(token: string): Promise<ProvisioningInvitationPreviewResponse> {
    return api<ProvisioningInvitationPreviewResponse>(`${BASE}/preview`, {
      method: 'POST',
      body: { token },
    })
  }

  /** 招待トークンを承諾する（ADMIN役割+membership付与→スコープACTIVE化）。 */
  async function accept(token: string): Promise<ProvisioningInvitationAcceptResponse> {
    return api<ProvisioningInvitationAcceptResponse>(`${BASE}/accept`, {
      method: 'POST',
      body: { token },
    })
  }

  return {
    preview,
    accept,
  }
}
