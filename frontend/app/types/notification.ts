export type NotificationPriority = 'LOW' | 'NORMAL' | 'HIGH' | 'URGENT'
export type NotificationScopeType = 'TEAM' | 'ORGANIZATION' | 'PERSONAL' | 'SYSTEM'

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
  scopeId: number | null
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
    total: number
    byScope: Array<{
      scopeType: NotificationScopeType
      scopeId: number
      scopeName: string
      count: number
    }>
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
  scopeId: number
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
