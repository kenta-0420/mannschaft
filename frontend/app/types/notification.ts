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

/**
 * スコープ別通知設定（F04.3 ハイブリッド方式）。
 * BE: PreferenceResponse（scope は {scopeType, scopeId} のネスト）をフラット化して扱う。
 * 受信可否は単一トグル isEnabled のみ（旧 isMuted / inAppEnabled / pushEnabled は廃止）。
 */
export interface NotificationPreference {
  scopeType: 'TEAM' | 'ORGANIZATION'
  scopeId: string
  scopeName: string
  isEnabled: boolean
}

/**
 * 通知種別ごとの設定（F04.3 ハイブリッド方式・カタログ）。
 * channelOverride=false: 単一トグル（isEnabled）。
 * channelOverride=true: Dual（inAppEnabled / pushEnabled）。
 * isLocked=true（URGENT）: トグル無効化。
 */
export interface NotificationTypePreference {
  notificationType: string
  label: string
  priority: NotificationPriority
  isEnabled: boolean
  channelOverride: boolean
  inAppEnabled: boolean
  pushEnabled: boolean
  isLocked: boolean
}

/**
 * グローバル通知設定（F04.3）。優先度による自動配信。
 */
export interface NotificationSettings {
  priorityAutoDelivery: boolean
}

/**
 * 通知種別設定の一括更新エントリ（PUT /api/v1/notification-type-preferences）。
 * channelOverride=false なら isEnabled、true なら inAppEnabled / pushEnabled を送る。
 */
export interface NotificationTypePreferenceUpdateEntry {
  notificationType: string
  channelOverride: boolean
  isEnabled?: boolean
  inAppEnabled?: boolean
  pushEnabled?: boolean
}
