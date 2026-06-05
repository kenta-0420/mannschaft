export type NotificationPriority = 'LOW' | 'NORMAL' | 'HIGH' | 'URGENT'
export type NotificationScopeType = 'TEAM' | 'ORGANIZATION' | 'PERSONAL' | 'SYSTEM' | 'FRIEND_TEAM' | 'FRIEND_FOLDER'

export interface NotificationActor {
  id: number
  displayName: string
  avatarUrl: string | null
}

export interface NotificationResponse {
  id: number
  notificationType: string
  priority: NotificationPriority
  title: string
  body: string | null
  sourceType: string
  sourceId: number | null
  scopeType: NotificationScopeType
  scopeId: string | null
  scopeName: string | null
  actionUrl: string | null
  actor: NotificationActor | null
  isRead: boolean
  readAt: string | null
  snoozedUntil: string | null
  createdAt: string
}

export interface UnreadCountResponse {
  data: {
    unreadCount: number
  }
}

export interface NotificationListResponse {
  data: NotificationResponse[]
  meta: {
    nextCursor: number | null
    limit: number
    hasNext: boolean
  }
}

export interface NotificationPreference {
  scopeType: 'TEAM' | 'ORGANIZATION'
  scopeId: string
  scopeName: string
  isMuted: boolean
  inAppEnabled: boolean
  pushEnabled: boolean
}

export interface NotificationTypePreference {
  notificationType: string
  label: string
  category: string
  inAppEnabled: boolean
  pushEnabled: boolean
}
