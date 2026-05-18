/**
 * F09.17 メッセージ型キャンペーン — 受信者向け最小型
 *
 * 注: このファイルは Phase 11-c-1（基盤）と並行作成された雛形。
 * 広告主向けの完全型は 11-c-3 で別途定義される。
 */

export type AdChannelType = 'ANNOUNCEMENT' | 'EMAIL' | 'PUSH' | 'BANNER'

export type AdCampaignStatus =
  | 'DRAFT'
  | 'REVIEW'
  | 'APPROVED'
  | 'SCHEDULED'
  | 'DELIVERING'
  | 'PAUSED'
  | 'COMPLETED'
  | 'BLOCKED'
  | 'CANCELLED'

/**
 * 配信履歴アイテム（受信者視点）
 * GET /api/v1/me/ad-deliveries の items 要素
 */
export interface AdDeliveryHistoryItem {
  /** 配信レコードの ID（UUIDv7） */
  id: string
  /** キャンペーン ID（UUIDv7） */
  campaignId: string
  /** キャンペーン名（広告主が設定した name） */
  campaignName: string
  /** 配信チャネル種別 */
  channelType: AdChannelType
  /** 配信日時 (ISO 8601) */
  deliveredAt: string
  /** 開封・既読日時 */
  readAt: string | null
  /** クリック・タップ日時 */
  clickedAt: string | null
  /** 広告主表示名（PII 含まず、組織名等） */
  advertiserDisplayName: string
}

export interface AdDeliveryHistoryResponse {
  items: AdDeliveryHistoryItem[]
  nextCursor: string | null
}
