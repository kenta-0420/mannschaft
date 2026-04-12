import type {
  ConfirmableNotificationSettings,
  ConfirmableNotificationSummary,
  ConfirmableNotificationDetail,
  ConfirmableNotificationRecipientItem,
  ConfirmableNotificationTemplate,
  CreateConfirmableNotificationRequest,
  UpdateConfirmableNotificationSettingsRequest,
  CreateConfirmableNotificationTemplateRequest,
} from '~/types/confirmable'

export function useConfirmableNotificationApi() {
  const api = useApi()

  /**
   * スコープタイプに応じた通知ベースURLを生成する
   * TEAM: /api/v1/teams/{scopeId}/confirmable-notifications
   * ORGANIZATION: /api/v1/organizations/{scopeId}/confirmable-notifications
   */
  function buildBaseUrl(scopeType: 'TEAM' | 'ORGANIZATION', scopeId: number): string {
    const prefix = scopeType === 'TEAM' ? 'teams' : 'organizations'
    return `/api/v1/${prefix}/${scopeId}/confirmable-notifications`
  }

  /**
   * スコープタイプに応じた設定URLを生成する
   * TEAM: /api/v1/teams/{scopeId}/confirmable-notification-settings
   * ORGANIZATION: /api/v1/organizations/{scopeId}/confirmable-notification-settings
   */
  function buildSettingsUrl(scopeType: 'TEAM' | 'ORGANIZATION', scopeId: number): string {
    const prefix = scopeType === 'TEAM' ? 'teams' : 'organizations'
    return `/api/v1/${prefix}/${scopeId}/confirmable-notification-settings`
  }

  /**
   * スコープタイプに応じたテンプレートURLを生成する
   * TEAM: /api/v1/teams/{scopeId}/confirmable-notification-templates
   * ORGANIZATION: /api/v1/organizations/{scopeId}/confirmable-notification-templates
   */
  function buildTemplateBaseUrl(scopeType: 'TEAM' | 'ORGANIZATION', scopeId: number): string {
    const prefix = scopeType === 'TEAM' ? 'teams' : 'organizations'
    return `/api/v1/${prefix}/${scopeId}/confirmable-notification-templates`
  }

  // === Settings ===

  /** 確認通知設定を取得する（存在しない場合はデフォルト値で作成） */
  async function getSettings(scopeType: 'TEAM' | 'ORGANIZATION', scopeId: number) {
    return api<{ data: ConfirmableNotificationSettings }>(buildSettingsUrl(scopeType, scopeId))
  }

  /** 確認通知設定を更新する */
  async function updateSettings(
    scopeType: 'TEAM' | 'ORGANIZATION',
    scopeId: number,
    data: UpdateConfirmableNotificationSettingsRequest,
  ) {
    return api<{ data: ConfirmableNotificationSettings }>(buildSettingsUrl(scopeType, scopeId), {
      method: 'PUT',
      body: data,
    })
  }

  // === Notifications ===

  /** 確認通知を送信する */
  async function sendNotification(
    scopeType: 'TEAM' | 'ORGANIZATION',
    scopeId: number,
    data: CreateConfirmableNotificationRequest,
  ) {
    return api<{ data: ConfirmableNotificationDetail }>(buildBaseUrl(scopeType, scopeId), {
      method: 'POST',
      body: data,
    })
  }

  /** 確認通知一覧を取得する */
  async function listNotifications(scopeType: 'TEAM' | 'ORGANIZATION', scopeId: number) {
    return api<{ data: ConfirmableNotificationSummary[] }>(buildBaseUrl(scopeType, scopeId))
  }

  /** 確認通知の詳細を取得する */
  async function getNotificationDetail(
    scopeType: 'TEAM' | 'ORGANIZATION',
    scopeId: number,
    notificationId: number,
  ) {
    return api<{ data: ConfirmableNotificationDetail }>(
      `${buildBaseUrl(scopeType, scopeId)}/${notificationId}`,
    )
  }

  /** 確認通知をキャンセルする */
  async function cancelNotification(
    scopeType: 'TEAM' | 'ORGANIZATION',
    scopeId: number,
    notificationId: number,
  ) {
    return api(`${buildBaseUrl(scopeType, scopeId)}/${notificationId}/cancel`, {
      method: 'PATCH',
    })
  }

  /** リマインダーを再送する */
  async function resendReminder(
    scopeType: 'TEAM' | 'ORGANIZATION',
    scopeId: number,
    notificationId: number,
  ) {
    return api(`${buildBaseUrl(scopeType, scopeId)}/${notificationId}/resend-reminder`, {
      method: 'POST',
    })
  }

  /** 確認通知の受信者一覧を取得する */
  async function getRecipients(
    scopeType: 'TEAM' | 'ORGANIZATION',
    scopeId: number,
    notificationId: number,
  ) {
    return api<{ data: ConfirmableNotificationRecipientItem[] }>(
      `${buildBaseUrl(scopeType, scopeId)}/${notificationId}/recipients`,
    )
  }

  /** 確認通知を確認済みにする（受信者が自分自身の通知を確認する） */
  async function confirmNotification(
    scopeType: 'TEAM' | 'ORGANIZATION',
    scopeId: number,
    notificationId: number,
  ) {
    return api(`${buildBaseUrl(scopeType, scopeId)}/${notificationId}/confirm`, {
      method: 'POST',
    })
  }

  // === Personal ===

  /** 自分宛の未確認の確認通知一覧を取得する */
  async function getPendingNotifications() {
    return api<{ data: ConfirmableNotificationSummary[] }>(
      '/api/v1/me/confirmable-notifications/pending',
    )
  }

  // === Templates ===

  /** テンプレート一覧を取得する */
  async function listTemplates(scopeType: 'TEAM' | 'ORGANIZATION', scopeId: number) {
    return api<{ data: ConfirmableNotificationTemplate[] }>(buildTemplateBaseUrl(scopeType, scopeId))
  }

  /** テンプレートを作成する */
  async function createTemplate(
    scopeType: 'TEAM' | 'ORGANIZATION',
    scopeId: number,
    data: CreateConfirmableNotificationTemplateRequest,
  ) {
    return api<{ data: ConfirmableNotificationTemplate }>(buildTemplateBaseUrl(scopeType, scopeId), {
      method: 'POST',
      body: data,
    })
  }

  /** テンプレートを更新する */
  async function updateTemplate(
    scopeType: 'TEAM' | 'ORGANIZATION',
    scopeId: number,
    templateId: number,
    data: CreateConfirmableNotificationTemplateRequest,
  ) {
    return api<{ data: ConfirmableNotificationTemplate }>(
      `${buildTemplateBaseUrl(scopeType, scopeId)}/${templateId}`,
      { method: 'PUT', body: data },
    )
  }

  /** テンプレートを削除する */
  async function deleteTemplate(
    scopeType: 'TEAM' | 'ORGANIZATION',
    scopeId: number,
    templateId: number,
  ) {
    return api(`${buildTemplateBaseUrl(scopeType, scopeId)}/${templateId}`, {
      method: 'DELETE',
    })
  }

  return {
    getSettings,
    updateSettings,
    sendNotification,
    listNotifications,
    getNotificationDetail,
    cancelNotification,
    resendReminder,
    getRecipients,
    confirmNotification,
    getPendingNotifications,
    listTemplates,
    createTemplate,
    updateTemplate,
    deleteTemplate,
  }
}
