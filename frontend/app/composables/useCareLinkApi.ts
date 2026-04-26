import type {
  CareLinkInvitationResponse,
  CareLinkNotifySettingsRequest,
  CareLinkResponse,
  InviteRecipientRequest,
  InviteWatcherRequest,
} from '~/types/careLink'

interface ApiResponse<T> {
  data: T
}

/**
 * F03.12 ケアリンク API クライアント。
 * /api/v1/me/care-links および /api/v1/care-links/invitations を扱う。
 */
export function useCareLinkApi() {
  const api = useApi()

  // ===========================================
  // 見守り者一覧（ケア対象者視点）
  // ===========================================

  async function getMyWatchers() {
    return api<ApiResponse<CareLinkResponse[]>>('/api/v1/me/care-links/watchers')
  }

  // ===========================================
  // ケア対象者一覧（見守り者視点）
  // ===========================================

  async function getMyRecipients() {
    return api<ApiResponse<CareLinkResponse[]>>('/api/v1/me/care-links/recipients')
  }

  // ===========================================
  // 保留中招待一覧
  // ===========================================

  async function getMyInvitations() {
    return api<ApiResponse<CareLinkResponse[]>>('/api/v1/me/care-links/invitations')
  }

  // ===========================================
  // 見守り者を招待
  // ===========================================

  async function inviteWatcher(body: InviteWatcherRequest) {
    return api<ApiResponse<CareLinkResponse>>('/api/v1/me/care-links/invite-watcher', {
      method: 'POST',
      body,
    })
  }

  // ===========================================
  // ケア対象者を招待
  // ===========================================

  async function inviteRecipient(body: InviteRecipientRequest) {
    return api<ApiResponse<CareLinkResponse>>('/api/v1/me/care-links/invite-recipient', {
      method: 'POST',
      body,
    })
  }

  // ===========================================
  // 通知設定更新
  // ===========================================

  async function updateNotifySettings(linkId: number, body: CareLinkNotifySettingsRequest) {
    return api<ApiResponse<CareLinkResponse>>(`/api/v1/me/care-links/${linkId}`, {
      method: 'PATCH',
      body,
    })
  }

  // ===========================================
  // ケアリンク解除
  // ===========================================

  async function deleteCareLink(linkId: number) {
    return api(`/api/v1/me/care-links/${linkId}`, { method: 'DELETE' })
  }

  // ===========================================
  // 招待情報取得（認証不要）
  // ===========================================

  async function getInvitationByToken(token: string) {
    return api<ApiResponse<CareLinkInvitationResponse>>(
      `/api/v1/care-links/invitations/${token}`,
    )
  }

  // ===========================================
  // 招待承認
  // ===========================================

  async function acceptInvitation(token: string) {
    return api<ApiResponse<CareLinkResponse>>(
      `/api/v1/care-links/invitations/${token}/accept`,
      { method: 'POST' },
    )
  }

  // ===========================================
  // 招待拒否
  // ===========================================

  async function rejectInvitation(token: string) {
    return api<ApiResponse<CareLinkResponse>>(
      `/api/v1/care-links/invitations/${token}/reject`,
      { method: 'POST' },
    )
  }

  return {
    getMyWatchers,
    getMyRecipients,
    getMyInvitations,
    inviteWatcher,
    inviteRecipient,
    updateNotifySettings,
    deleteCareLink,
    getInvitationByToken,
    acceptInvitation,
    rejectInvitation,
  }
}
