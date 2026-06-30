/**
 * F02.8 告知ウィザード・範囲テンプレート — 型定義
 */

import type { AnnouncementScopeType } from '~/types/announcement'

/** 告知ウィザードで選択可能なチャネル */
export type BroadcastChannel =
  | 'BULLETIN_THREAD'
  | 'TIMELINE_POST'
  | 'BLOG_POST'
  | 'TODO'
  | 'SCHEDULE'
  | 'SURVEY'

/** 告知対象ロール */
export type BroadcastTargetRole =
  | 'MEMBERS_AND_ABOVE'
  | 'SUPPORTERS_AND_ABOVE'
  | 'PUBLIC'

/** 告知優先度 */
export type BroadcastPriority = 'URGENT' | 'IMPORTANT' | 'NORMAL'

/** チャネル別コンテンツ入力 — 掲示板 */
export interface BulletinThreadContent {
  categoryId?: number
  title: string
  body: string
}

/** チャネル別コンテンツ入力 — タイムライン（BE アダプターは body を読む） */
export interface TimelinePostContent {
  body: string
}

/** チャネル別コンテンツ入力 — ブログ */
export interface BlogPostContent {
  title: string
  body: string
}

/** チャネル別コンテンツ入力 — TODO（body は BE で description として扱われる） */
export interface TodoContent {
  title: string
  body?: string
  description?: string
  dueDate?: string
  priority?: 'LOW' | 'MEDIUM' | 'HIGH'
}

/** チャネル別コンテンツ入力 — スケジュール */
export interface ScheduleContent {
  title: string
  /** 説明（任意・最大5000文字） */
  description: string | null
  /** ISO 8601 形式。allDay が true の場合も必須 */
  startAt: string | null
  /** ISO 8601 形式（任意） */
  endAt: string | null
  allDay: boolean
  attendanceRequired?: boolean
  /** 場所（任意・最大300文字） */
  location: string | null
}

/** チャネル別コンテンツ入力 — アンケート */
export interface SurveyContent {
  title: string
  /** 説明（任意・最大5000文字） */
  description: string | null
  questions: unknown[]
  /** 締切日時（任意・ISO 8601 形式） */
  closesAt: string | null
}

/** チャネル別コンテンツ入力（ユニオン型） */
export type BroadcastChannelContent =
  | BulletinThreadContent
  | TimelinePostContent
  | BlogPostContent
  | TodoContent
  | ScheduleContent
  | SurveyContent

/** POST /api/v1/{scopeType}/{scopeId}/broadcast リクエスト */
export interface BroadcastRequest {
  channel: BroadcastChannel
  targetRole: BroadcastTargetRole
  targetTeamIds?: string[] | null
  templateId?: number | null
  priority?: BroadcastPriority
  expiresAt?: string | null
  content: BroadcastChannelContent
}

/** POST /api/v1/{scopeType}/{scopeId}/broadcast レスポンス */
export interface BroadcastResponse {
  announcementFeedId: number
  channel: BroadcastChannel
  contentId: number
  contentUrl: string
  targetRole: BroadcastTargetRole
  targetTeamIds: string[] | null
  priority: BroadcastPriority
  createdAt: string
}

/** 範囲テンプレートの作成者情報 */
export interface TemplateCreatedBy {
  id: number
  displayName: string
}

/** 範囲テンプレートアイテム（GET レスポンス） */
export interface AnnouncementTemplate {
  id: number
  name: string
  targetRole: BroadcastTargetRole
  targetTeamIds: string[] | null
  preferredChannel: BroadcastChannel | null
  isDefault: boolean
  createdBy: TemplateCreatedBy | null
  createdAt: string
}

/** POST/PUT /api/v1/{scopeType}/{scopeId}/announcement-templates リクエスト */
export interface AnnouncementTemplateRequest {
  name: string
  targetRole: BroadcastTargetRole
  targetTeamIds?: string[] | null
  preferredChannel?: BroadcastChannel | null
  isDefault?: boolean
}

/** ウィザードのステップ状態 */
export type WizardStep = 1 | 2 | 3

/** ウィザードのフォーム状態 */
export interface WizardFormState {
  step: WizardStep
  targetRole: BroadcastTargetRole
  targetTeamIds: string[] | null
  selectedChannel: BroadcastChannel | null
  templateId: number | null
  priority: BroadcastPriority
  expiresAt: string | null
  content: Partial<BroadcastChannelContent>
}

// スコープ型の再エクスポート（import 元の明示用）
export type { AnnouncementScopeType }
