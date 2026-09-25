export type ConfirmableNotificationStatus = 'ACTIVE' | 'COMPLETED' | 'EXPIRED' | 'CANCELLED'
export type ConfirmableNotificationPriority = 'NORMAL' | 'HIGH' | 'URGENT'
export type ConfirmableConfirmedVia = 'APP' | 'TOKEN' | 'BULK'
export type ConfirmableNotificationDeliveryStatus = 'QUEUED' | 'DELIVERING' | 'DELIVERED' | 'PARTIALLY_FAILED'
export type ConfirmableTargetType = 'ORGANIZATION' | 'TEAM'
export type ConfirmableRecipientViewerRole = 'ADMIN' | 'CREATOR' | 'MEMBER'

export interface ConfirmableTarget {
  type: ConfirmableTargetType
  id: number
}

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
  scopeId: string
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
  scopeId: string
  deadlineAt: string | null
  totalRecipientCount: number
  confirmedCount: number
  createdAt: string
  /** この通知における未確認者リストの公開範囲 */
  unconfirmedVisibility: UnconfirmedVisibility
  deliveryStatus?: ConfirmableNotificationDeliveryStatus
  deliveredCount?: number
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
  displayName: string | null
  avatarUrl: string | null
  withdrawn: boolean
}

export interface ConfirmableNotificationRecipientPage {
  items: ConfirmableNotificationRecipientItem[]
  page: number
  size: number
  totalElements: number
  confirmedCount: number
  unconfirmedCount: number
  viewerRole: ConfirmableRecipientViewerRole
}

export interface ConfirmableRecipientGroup {
  id: string
  name: string
  targets: ConfirmableTarget[]
  createdAt: string
}

export interface ConfirmableNotificationTemplate {
  id: number
  scopeType: 'TEAM' | 'ORGANIZATION'
  scopeId: string
  name: string
  title: string
  body: string | null
  defaultPriority: ConfirmableNotificationPriority
  defaultRecipientGroupId: string | null
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
  targets?: ConfirmableTarget[]
  recipientGroupId?: string
  /** 未確認者リストの公開範囲（未指定時はサーバ側でスコープ設定にフォールバック） */
  unconfirmedVisibility?: UnconfirmedVisibility | null
}

export interface ConfirmableNotificationSendAccepted {
  id: number
  deliveryStatus: ConfirmableNotificationDeliveryStatus
  estimatedRecipientCount: number
}

export interface ConfirmableRecipientPreviewRequest {
  targets?: ConfirmableTarget[]
  recipientGroupId?: string
}

export interface ConfirmableRecipientPreview {
  estimatedRecipientCount: number
}

export interface CreateConfirmableRecipientGroupRequest {
  name: string
  targets: ConfirmableTarget[]
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
  defaultRecipientGroupId?: string | null
}
