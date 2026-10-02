/**
 * F09.17 受信者の広告受信設定 / 配信履歴 / 通報 — 型定義
 *
 * <p>backend `com.mannschaft.app.advertising.campaign.controller.UserAdPreferencesController` ほか
 * 受信者向け API（/api/v1/me/...）のレスポンス・リクエスト型に対応する。</p>
 */

import type { AdChannelType } from './adMessagingCampaign'

// === Enums ===

/**
 * 広告受信チャネル enum（{@code AdChannelType} と同集合だが、
 * 設定 UI 上は受信者視点でこちらを使う）。
 */
export type AdReceiveChannel = AdChannelType

/** 通報理由（backend {@code AdReportReasonCode}） */
export type AdReportReason =
  | 'OFFENSIVE'
  | 'MISLEADING'
  | 'SPAM'
  | 'IRRELEVANT'
  | 'OTHER'

// === User Ad Preferences ===

/**
 * 自分の広告受信設定。
 * backend {@code UserAdPreferenceResponse} に対応。
 */
export interface UserAdPreferences {
  id: string
  acceptAnnouncementAds: boolean
  acceptEmailAds: boolean
  acceptPushAds: boolean
  acceptBannerAds: boolean
  blockedAdvertiserAccountIds: number[]
  consentedAt: string | null
  unsubscribeTokenVersion: number
  updatedAt: string
}

/**
 * 自分の広告受信設定更新リクエスト（部分更新）。
 * backend {@code UpdateUserAdPreferencesRequest} に対応。
 *
 * <p>各 {@code accept*Ads} / {@code blockedAdvertiserAccountIds} は null/undefined の場合
 * 現在値を維持する。{@code rotateUnsubscribeTokens=true} で
 * unsubscribe トークンバージョンが +1 される。</p>
 */
export interface UpdateAdPreferencesRequest {
  acceptAnnouncementAds?: boolean | null
  acceptEmailAds?: boolean | null
  acceptPushAds?: boolean | null
  acceptBannerAds?: boolean | null
  blockedAdvertiserAccountIds?: number[] | null
  rotateUnsubscribeTokens?: boolean | null
}

// === Ad Delivery (my history) ===

/**
 * 自分宛の広告配信履歴 1 件。
 *
 * <p>backend で受信者用エンドポイント実装が完了するまでの暫定型。
 * 実装が固まったらここを SoT として揃える。</p>
 */
export interface AdDelivery {
  id: string
  campaignId: string
  advertiserAccountId: number
  channelType: AdChannelType
  subject: string | null
  excerpt: string | null
  deliveredAt: string
  openedAt: string | null
  clickedAt: string | null
}

export interface AdDeliveryListResponse {
  data: AdDelivery[]
  meta: {
    nextCursor: string | null
    limit: number
    hasNext: boolean
  }
}

// === Reporting ===

/**
 * 通報作成リクエスト（F09.19.9・{@code POST /api/v1/me/ad-reports}）。
 *
 * <p>{@code campaignId}（メッセージ型・UUID）と {@code operationalCampaignId}（運用型・数値）は XOR。
 * 片方のみ指定する（両方指定・両方 null は 400 / AD_032）。</p>
 */
export interface CreateAdReportRequest {
  /** メッセージ型キャンペーン（ad_messaging_campaigns.id・UUID）。運用型時は省略 */
  campaignId?: string | null
  /** 運用型キャンペーン（ad_campaigns.id・数値）。メッセージ型時は省略 */
  operationalCampaignId?: number | null
  /** 通報元チャネル（運用型は常に BANNER） */
  channelType: AdChannelType
  /** 通報理由 */
  reasonCode: AdReportReason
  /** 自由記述（null 可・500 文字以内） */
  comment?: string | null
}

/** 通報作成レスポンス（201: { data: { id, status, createdAt } }）。 */
export interface AdReportResponse {
  id: string
  status: 'NEW' | 'REVIEWING' | 'RESOLVED' | 'DISMISSED'
  createdAt: string
}

// === Public Unsubscribe SPA (F09.17 残課題 4) ===

/**
 * 公開 unsubscribe SPA からの POST リクエスト。
 * backend {@code UnsubscribeRequest} に対応。
 */
export interface UnsubscribeRequest {
  /** メール末尾リンクで配布される unsubscribe JWT */
  token: string
  /** OFF にしたいチャネル一覧（最低 1 件） */
  channels: AdReceiveChannel[]
}

/**
 * 公開 unsubscribe SPA POST レスポンス。
 * backend {@code UnsubscribeResultResponse} に対応。
 */
export interface UnsubscribeResultResponse {
  /** 今回 OFF にしたチャネル */
  disabledChannels: AdReceiveChannel[]
  /** まだ ON のチャネル */
  remainingActiveChannels: AdReceiveChannel[]
  /** フロント i18n キー */
  messageKey: string
}
