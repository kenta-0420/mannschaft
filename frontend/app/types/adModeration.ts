/**
 * F09.17 SYSTEM_ADMIN 審査キュー / 通報処理 — 型定義
 *
 * <p>backend `SystemAdminAdCampaignController` および将来追加される通報処理
 * Controller に対応する。SYSTEM_ADMIN 権限を持つユーザーのみが利用する。</p>
 */

import type {
  AdMessagingCampaignModerationAction,
  AdMessagingCampaignModerationStatus,
  AdMessagingCampaignStatus,
} from './adMessagingCampaign'
import type { AdReportReason } from './adPreferences'

// === Review Queue ===

/**
 * SYSTEM_ADMIN 審査キューの 1 件。
 * backend {@code ReviewQueueItemResponse} に対応。
 *
 * <p>{@code moderationStatus IN (PENDING, AUTO_FLAGGED)} の対象のみが乗る。</p>
 */
export interface AdReviewQueueItem {
  campaignId: string
  organizationId: number
  advertiserAccountId: number
  name: string
  status: AdMessagingCampaignStatus
  moderationStatus: AdMessagingCampaignModerationStatus
  /** 自動 NG 検知の理由（{@code AUTO_FLAGGED} 時のみ非 null を想定） */
  autoFlagReason?: string | null
  createdAt: string
}

// === Approve / Block ===

/**
 * キャンペーン承認リクエスト。
 * backend {@code ApproveCampaignRequest}（現状ボディなし）に対応。
 * 将来のコメント追加に備えて型としては存在させておく。
 */
export interface ApproveCampaignRequest {
  comment?: string | null
}

/**
 * キャンペーンブロックリクエスト。
 * backend {@code BlockCampaignRequest} に対応。
 *
 * @property reason ブロック理由（必須・最大 500 文字）
 */
export interface BlockCampaignRequest {
  reason: string
}

// === Moderation Log ===

/**
 * モデレーション操作ログ 1 件（管理画面の操作履歴表示用）。
 */
export interface AdCampaignModerationLog {
  id: string
  campaignId: string
  action: AdMessagingCampaignModerationAction
  moderatorUserId: number | null
  reason: string | null
  createdAt: string
}

// === User Reports (admin view) ===

/**
 * ユーザーからの広告通報（管理側ビュー）。
 *
 * <p>auto_suspend_candidate は短時間に複数件報告された等で
 * バックエンドが自動 SUSPEND 候補と判定したフラグ。</p>
 */
export interface AdUserReport {
  id: string
  /** メッセージ型キャンペーン ID（運用型通報時は null）。F09.19.9 で nullable 化 */
  campaignId: string | null
  /** 運用型キャンペーン ID（メッセージ型通報時は null）。F09.19.9 で追加 */
  operationalCampaignId: number | null
  userId: number | null
  reason: AdReportReason
  detail: string | null
  status: 'NEW' | 'REVIEWING' | 'RESOLVED' | 'DISMISSED'
  autoSuspendCandidate: boolean
  reportedAt: string
}

// === List parameters ===

export interface AdReviewQueueListParams {
  page?: number
  size?: number
}
