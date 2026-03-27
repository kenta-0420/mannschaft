import type {
  NotificationListResponse,
  UnreadCountResponse,
  NotificationPreference,
  NotificationTypePreference,
} from '~/types/notification'

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
    return api(`/api/v1/notifications/${notificationId}/read`, { method: 'PATCH' })
  }

  async function markAsUnread(notificationId: number) {
    return api(`/api/v1/notifications/${notificationId}/unread`, { method: 'PATCH' })
  }

  async function snooze(notificationId: number, duration: string) {
    return api(`/api/v1/notifications/${notificationId}/snooze`, {
      method: 'PATCH',
      body: { duration },
    })
  }

  async function markAllAsRead() {
    return api('/api/v1/notifications/read-all', { method: 'PATCH' })
  }

  // === Preferences (scope) ===
  async function getPreferences() {
    return api<{ data: NotificationPreference[] }>('/api/v1/notification-preferences')
  }

  async function updateTeamPreference(teamId: number, settings: Record<string, unknown>) {
    return api(`/api/v1/notification-preferences/teams/${teamId}`, {
      method: 'PUT',
      body: settings,
    })
  }

  async function updateOrgPreference(orgId: number, settings: Record<string, unknown>) {
    return api(`/api/v1/notification-preferences/organizations/${orgId}`, {
      method: 'PUT',
      body: settings,
    })
  }

  // === Preferences (type) ===
  async function getTypePreferences() {
    return api<{ data: NotificationTypePreference[] }>('/api/v1/notification-type-preferences')
  }

  async function updateTypePreferences(preferences: Array<{ notificationType: string; inAppEnabled: boolean; pushEnabled: boolean }>) {
    return api('/api/v1/notification-type-preferences', {
      method: 'PUT',
      body: { preferences },
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

  return {
    getNotifications,
    getUnreadCount,
    markAsRead,
    markAsUnread,
    snooze,
    markAllAsRead,
    getPreferences,
    updateTeamPreference,
    updateOrgPreference,
    getTypePreferences,
    updateTypePreferences,
    registerPushSubscription,
    unregisterPushSubscription,
  }
}
