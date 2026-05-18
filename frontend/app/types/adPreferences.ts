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

/** 通報作成リクエスト */
export interface CreateAdReportRequest {
  campaignId: string
  reason: AdReportReason
  detail?: string | null
}

export interface AdReportResponse {
  id: string
  campaignId: string
  userId: number
  reason: AdReportReason
  detail: string | null
  createdAt: string
}
