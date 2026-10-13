/**
 * F02.8 告知ウィザード・範囲テンプレート — 型定義
 */

import type { AnnouncementScopeType } from '~/types/announcement'
import type { components } from '~/types/generated'

/** 送信時の宛先指定の記録（送信履歴の「宛先: …（送信時 N チーム）」。生成型を使う） */
export type BroadcastTargetAudience = components['schemas']['TargetAudience']

/** 宛先の選び方（F01.2.1 §8.1）。GROUPS は組織・グループ機能 on のときだけ選べる */
export type BroadcastTargetMode = 'ALL' | 'TEAMS' | 'GROUPS'

/** 並び順の範囲指定。「以前」は from=null、「以降」は to=null */
export interface BroadcastGroupRange {
  fromGroupId: string | null
  toGroupId: string | null
}

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
  /** 設問（告知ウィザードでは未送信。作成後に編集画面で追加する前提のため任意） */
  questions?: unknown[]
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
  /** 「チームグループで選ぶ」の個別チェック（targetTeamIds とは併用不可） */
  targetGroupIds?: string[]
  targetGroupRange?: BroadcastGroupRange
  includeUnassigned?: boolean
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
  targetGroupIds?: string[] | null
  includeUnassigned?: boolean
  /** 送信時の宛先指定の記録（絞り込みなしなら null / 未設定） */
  targetAudience?: BroadcastTargetAudience | null
  /** 警告（例: TEMPLATE_GROUPS_REMOVED:2） */
  warnings?: string[]
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
  targetGroupIds?: string[] | null
  targetGroupRange?: BroadcastGroupRange | null
  includeUnassigned?: boolean
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
  targetGroupIds?: string[]
  targetGroupRange?: BroadcastGroupRange
  includeUnassigned?: boolean
  preferredChannel?: BroadcastChannel | null
  isDefault?: boolean
}

/** ウィザードのステップ状態 */
export type WizardStep = 1 | 2 | 3

/** ウィザードのフォーム状態 */
export interface WizardFormState {
  step: WizardStep
  targetRole: BroadcastTargetRole
  targetMode: BroadcastTargetMode
  /** TEAMS モードのときだけ配列。ALL / GROUPS では null */
  targetTeamIds: string[] | null
  targetGroupIds: string[]
  targetGroupRange: BroadcastGroupRange | null
  includeUnassigned: boolean
  selectedChannel: BroadcastChannel | null
  templateId: number | null
  priority: BroadcastPriority
  expiresAt: string | null
  content: Partial<BroadcastChannelContent>
}

// スコープ型の再エクスポート（import 元の明示用）
export type { AnnouncementScopeType }
