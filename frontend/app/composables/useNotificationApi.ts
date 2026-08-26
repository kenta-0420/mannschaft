import type {
  NotificationListResponse,
  UnreadCountResponse,
  NotificationPreference,
  NotificationTypePreference,
  NotificationTypePreferenceUpdateEntry,
  NotificationSettings,
} from '~/types/notification'

/**
 * BE PreferenceResponse は scope を {scopeType, scopeId} のネストで返す。
 * FE はフラットな NotificationPreference で扱うため、取得時に正規化する。
 */
interface RawPreferenceResponse {
  scope: { scopeType: string; scopeId: number | null }
  scopeName: string | null
  isEnabled: boolean
}

interface NotificationListParams {
  cursor?: number
  limit?: number
  isRead?: boolean
  scopeType?: string
  scopeId?: number
  notificationType?: string
}

export function useNotificationApi() {
  const api = useApi()

  function buildQuery(params: Record<string, unknown>): string {
    const query = new URLSearchParams()
    for (const [key, value] of Object.entries(params)) {
      if (value !== undefined && value !== null) {
        query.set(key, String(value))
      }
    }
    return query.toString()
  }

  // === Notifications ===
  async function getNotifications(params?: NotificationListParams) {
    const qs = buildQuery({
      cursor: params?.cursor,
      limit: params?.limit,
      is_read: params?.isRead,
      scope_type: params?.scopeType,
      scope_id: params?.scopeId,
      notification_type: params?.notificationType,
    })
    return api<NotificationListResponse>(`/api/v1/notifications?${qs}`)
  }

  async function getUnreadCount() {
    return api<UnreadCountResponse>('/api/v1/notifications/unread-count')
  }

  async function markAsRead(notificationId: number) {
    return api(`/api/v1/notifications/${notificationId}/read`, { method: 'POST' })
  }

  async function markAsUnread(notificationId: number) {
    return api(`/api/v1/notifications/${notificationId}/unread`, { method: 'POST' })
  }

  /**
   * 通知をスヌーズする。
   * F04.11 設計書 02_api_design.md §3.3 に合わせ snoozedUntil（ISO-8601 文字列）を送る。
   * （旧シグネチャは duration: string だったが、呼び出し側ゼロのため破壊なし）
   */
  async function snooze(notificationId: number, snoozedUntil: string) {
    return api(`/api/v1/notifications/${notificationId}/snooze`, {
      method: 'POST',
      body: { snoozedUntil },
    })
  }

  async function markAllAsRead() {
    return api('/api/v1/notifications/read-all', { method: 'POST' })
  }

  // === Preferences (scope) ===
  async function getPreferences(): Promise<{ data: NotificationPreference[] }> {
    const res = await api<{ data: RawPreferenceResponse[] }>('/api/v1/notification-preferences')
    return {
      data: res.data.map((p) => ({
        scopeType: p.scope.scopeType as NotificationPreference['scopeType'],
        scopeId: String(p.scope.scopeId ?? ''),
        scopeName: p.scopeName ?? '',
        isEnabled: p.isEnabled,
      })),
    }
  }

  /**
   * スコープ別通知設定を更新する。
   * BE: PUT /api/v1/notification-preferences（{scopeType, scopeId, isEnabled}）。
   */
  async function updatePreferences(body: {
    scopeType: string
    scopeId: string
    isEnabled: boolean
  }) {
    return api('/api/v1/notification-preferences', {
      method: 'PUT',
      body: {
        scopeType: body.scopeType,
        scopeId: Number(body.scopeId),
        isEnabled: body.isEnabled,
      },
    })
  }

  // === Matching notification preferences ===
  async function getMatchingNotificationPreferences(teamId: string) {
    return api(`/api/v1/teams/${teamId}/matching/notification-preferences`)
  }

  async function updateMatchingNotificationPreferences(
    teamId: string,
    body: Record<string, unknown>,
  ) {
    return api(`/api/v1/teams/${teamId}/matching/notification-preferences`, {
      method: 'PUT',
      body,
    })
  }

  // === Preferences (type) ===
  async function getTypePreferences() {
    return api<{ data: NotificationTypePreference[] }>('/api/v1/notification-type-preferences')
  }

  /**
   * 通知種別設定を一括更新する（ハイブリッド契約）。
   * channelOverride=false なら isEnabled、true なら inAppEnabled / pushEnabled を含める。
   */
  async function updateTypePreferences(preferences: NotificationTypePreferenceUpdateEntry[]) {
    return api('/api/v1/notification-type-preferences', {
      method: 'PUT',
      body: { preferences },
    })
  }

  // === Global settings ===
  async function getSettings() {
    return api<{ data: NotificationSettings }>('/api/v1/notification-settings')
  }

  async function updateSettings(body: NotificationSettings) {
    return api<{ data: NotificationSettings }>('/api/v1/notification-settings', {
      method: 'PUT',
      body,
    })
  }

  // === Push Subscription ===
  async function registerPushSubscription(subscription: PushSubscriptionJSON) {
    return api('/api/v1/push-subscriptions', {
      method: 'POST',
      body: subscription,
    })
  }

  async function unregisterPushSubscription() {
    return api('/api/v1/push-subscriptions', { method: 'DELETE' })
  }

  // === Admin Stats ===
  async function getAdminNotificationStats() {
    return api<{ data: unknown }>('/api/v1/admin/notifications/stats')
  }

  return {
    getNotifications,
    getUnreadCount,
    markAsRead,
    markAsUnread,
    snooze,
    markAllAsRead,
    getPreferences,
    updatePreferences,
    getMatchingNotificationPreferences,
    updateMatchingNotificationPreferences,
    getTypePreferences,
    updateTypePreferences,
    getSettings,
    updateSettings,
    registerPushSubscription,
    unregisterPushSubscription,
    getAdminNotificationStats,
  }
}
