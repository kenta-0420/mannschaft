export type ConfirmableNotificationStatus = 'ACTIVE' | 'COMPLETED' | 'EXPIRED' | 'CANCELLED'
export type ConfirmableNotificationPriority = 'NORMAL' | 'HIGH' | 'URGENT'
export type ConfirmableConfirmedVia = 'APP' | 'TOKEN' | 'BULK'

/**
 * 未確認者リストの公開範囲
 * - HIDDEN: 表示しない
 * - CREATOR_AND_ADMIN: 作成者・管理者のみ
 * - ALL_MEMBERS: 全員に公開
 */
export type UnconfirmedVisibility = 'HIDDEN' | 'CREATOR_AND_ADMIN' | 'ALL_MEMBERS'

export interface ConfirmableNotificationSettings {
  id?: number
  scopeType: 'TEAM' | 'ORGANIZATION'
  scopeId: number
  defaultFirstReminderMinutes: number | null
  defaultSecondReminderMinutes: number | null
  senderAlertThresholdPercent: number
  /** デフォルトの未確認者リスト公開範囲 */
  defaultUnconfirmedVisibility: UnconfirmedVisibility
}

export interface ConfirmableNotificationSummary {
  id: number
  title: string
  priority: ConfirmableNotificationPriority
  status: ConfirmableNotificationStatus
  scopeType: 'TEAM' | 'ORGANIZATION'
  scopeId: number
  deadlineAt: string | null
  totalRecipientCount: number
  confirmedCount: number
  createdAt: string
  /** この通知における未確認者リストの公開範囲 */
  unconfirmedVisibility: UnconfirmedVisibility
}

export interface ConfirmableNotificationDetail extends ConfirmableNotificationSummary {
  body: string | null
  actionUrl: string | null
  firstReminderMinutes: number | null
  secondReminderMinutes: number | null
  cancelledAt: string | null
  completedAt: string | null
  expiredAt: string | null
  createdBy: number | null
}

export interface ConfirmableNotificationRecipientItem {
  id: number
  userId: number
  isConfirmed: boolean
  confirmedAt: string | null
  confirmedVia: ConfirmableConfirmedVia | null
  firstReminderSentAt: string | null
  secondReminderSentAt: string | null
  excludedAt: string | null
  createdAt: string
}

export interface ConfirmableNotificationTemplate {
  id: number
  scopeType: 'TEAM' | 'ORGANIZATION'
  scopeId: number
  name: string
  title: string
  body: string | null
  defaultPriority: ConfirmableNotificationPriority
  createdAt: string
}

export interface CreateConfirmableNotificationRequest {
  title: string
  body?: string
  priority: ConfirmableNotificationPriority
  deadlineAt?: string
  firstReminderMinutes?: number
  secondReminderMinutes?: number
  actionUrl?: string
  templateId?: number
  recipientUserIds: number[]
  /** 未確認者リストの公開範囲（未指定時はサーバ側でスコープ設定にフォールバック） */
  unconfirmedVisibility?: UnconfirmedVisibility | null
}

export interface UpdateConfirmableNotificationSettingsRequest {
  defaultFirstReminderMinutes: number | null
  defaultSecondReminderMinutes: number | null
  senderAlertThresholdPercent: number
  /** デフォルトの未確認者リスト公開範囲 */
  defaultUnconfirmedVisibility: UnconfirmedVisibility
}

export interface CreateConfirmableNotificationTemplateRequest {
  name: string
  title: string
  body?: string
  defaultPriority: ConfirmableNotificationPriority
}
