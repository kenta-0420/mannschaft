/**
 * F09.17 受信者向け広告受信設定 — 型定義
 *
 * 注: このファイルは Phase 11-c-1（基盤）と並行作成された雛形。
 * 11-c-1 マージ時に統合される可能性がある。
 */

/**
 * 受信設定レスポンス（GET /api/v1/me/ad-preferences）
 */
export interface AdPreferencesResponse {
  acceptAnnouncementAds: boolean
  acceptEmailAds: boolean
  acceptPushAds: boolean
  acceptBannerAds: boolean
  blockedAdvertiserAccountIds: number[]
  consentedAt: string | null
  unsubscribeTokenVersion: number
}

/**
 * 受信設定更新リクエスト（PUT /api/v1/me/ad-preferences）
 * 全フィールド optional。差分のみ送る。
 */
export interface UpdateAdPreferencesRequest {
  acceptAnnouncementAds?: boolean
  acceptEmailAds?: boolean
  acceptPushAds?: boolean
  acceptBannerAds?: boolean
  blockedAdvertiserAccountIds?: number[]
  /** true でサーバ側で unsubscribe_token_version をインクリメント */
  rotateUnsubscribeToken?: boolean
}
