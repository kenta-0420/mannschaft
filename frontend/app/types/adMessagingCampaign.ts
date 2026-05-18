/**
 * F09.17 メッセージ型キャンペーン — 型定義
 *
 * <p>backend `com.mannschaft.app.advertising.campaign` パッケージの DTO / enum に対応する。
 * 既存 `types/advertiser.ts` (F09.11 バナー広告系) とは別系統のため、衝突回避で
 * 新規ファイルとして分離している。</p>
 *
 * <p>`types/generated/index.ts` が openapi 同期されるまでの暫定 SoT。
 * Backend Controller を変更した際はこちらも追従させること。</p>
 */

// === Enums ===

/** キャンペーン状態（backend {@code AdCampaignStatus}） */
export type AdMessagingCampaignStatus =
  | 'DRAFT'
  | 'REVIEW'
  | 'APPROVED'
  | 'SCHEDULED'
  | 'DELIVERING'
  | 'PAUSED'
  | 'COMPLETED'
  | 'BLOCKED'
  | 'CANCELLED'

/** モデレーション状態（backend {@code AdModerationStatus}） */
export type AdMessagingCampaignModerationStatus =
  | 'PENDING'
  | 'AUTO_PASSED'
  | 'AUTO_FLAGGED'
  | 'APPROVED'
  | 'BLOCKED'

/** モデレーション操作ログ種別（backend {@code AdModerationAction}） */
export type AdMessagingCampaignModerationAction =
  | 'APPROVED'
  | 'BLOCKED'
  | 'UNBLOCKED'
  | 'AUTO_FLAGGED'
  | 'AUTO_PASSED'

/** 配信チャネル（backend {@code AdChannelType}） */
export type AdChannelType = 'ANNOUNCEMENT' | 'EMAIL' | 'PUSH' | 'BANNER'

/** ターゲティングセグメント種別（backend {@code AdSegmentType}） */
export type AdSegmentType =
  | 'AGE_RANGE'
  | 'GENDER'
  | 'REGION_PREFECTURE'
  | 'REGION_CITY'
  | 'INTEREST_TAG'
  | 'ORG_TYPE'
  | 'LOCALE'
  | 'DEVICE'

/** ターゲティングセグメントの INCLUDE/EXCLUDE モード（backend {@code AdSegmentInclusionMode}） */
export type AdSegmentInclusionMode = 'INCLUDE' | 'EXCLUDE'

/** 推定リーチレンジ enum（backend {@code EstimatedReachRange}） */
export type EstimatedReachRange =
  | 'UNDER_100'
  | 'RANGE_100_500'
  | 'RANGE_500_1K'
  | 'RANGE_1K_5K'
  | 'RANGE_5K_10K'
  | 'RANGE_10K_50K'
  | 'RANGE_50K_100K'
  | 'OVER_100K'

/**
 * セグメント値（backend は {@code Map<String,Object>} で JSON 受け）。
 * 型安全のため値は string / number / boolean / 配列 / 入れ子 record に限定する。
 */
export type AdSegmentValue = {
  [key: string]: AdSegmentValueLeaf | AdSegmentValueLeaf[] | AdSegmentValue
}
export type AdSegmentValueLeaf = string | number | boolean | null

// === Domain ===

/**
 * メッセージ型キャンペーン本体（一覧アイテム）。
 *
 * <p>backend {@code CampaignListItemResponse} に対応。
 * 一覧 API では channels / audienceSegments は含まれず、詳細 API で取得する。</p>
 */
export interface AdMessagingCampaignListItem {
  id: string
  name: string
  status: AdMessagingCampaignStatus
  moderationStatus: AdMessagingCampaignModerationStatus
  totalBudgetYen: number
  consumedBudgetYen: number
  startsAt: string
  endsAt: string
  scheduledTimezone: string
  frequencyCapOverride: number | null
  createdAt: string
  updatedAt: string
}

/**
 * メッセージ型キャンペーン詳細。
 * backend {@code CampaignDetailResponse} に対応。
 */
export interface AdMessagingCampaign extends AdMessagingCampaignListItem {
  advertiserAccountId: number
  blockedReason: string | null
  channels: AdMessagingCampaignChannel[]
  audienceSegments: AdMessagingCampaignAudienceSegment[]
}

/**
 * キャンペーンチャネル別コンテンツ。
 * backend {@code CampaignChannelResponse} に対応。
 */
export interface AdMessagingCampaignChannel {
  id: string
  campaignId: string
  channelType: AdChannelType
  locale: string
  subject: string | null
  bodyMarkdown: string
  imageUrl: string | null
  ctaLabel: string | null
  ctaUrl: string | null
  bannerCreativeId: number | null
  createdAt: string
  updatedAt: string
}

/**
 * キャンペーンターゲティングセグメント。
 * backend {@code AudienceSegmentResponse} に対応。
 */
export interface AdMessagingCampaignAudienceSegment {
  id: string
  campaignId: string
  segmentType: AdSegmentType
  segmentValue: AdSegmentValue
  inclusionMode: AdSegmentInclusionMode
  createdAt: string
}

// === Preview / Report ===

/**
 * キャンペーンのプレビュー / 推定リーチレスポンス。
 *
 * <p>backend {@code EstimatedReachRangeResponse} の構造をフロント側で拡張し、
 * チャネル別カウントも含めた表示用にまとめる。
 * 個別ユーザー特定リスク回避のため reach は必ずレンジ enum を含む。</p>
 */
export interface AdCampaignPreviewResponse {
  range: EstimatedReachRange
  label: string
  channelCounts?: Partial<Record<AdChannelType, number>>
}

/** キャンペーン KPI レポート（日次推移込み） */
export interface AdCampaignReport {
  campaignId: string
  range: { from: string; to: string }
  totals: AdCampaignReportTotals
  daily: AdCampaignReportDailyPoint[]
  byChannel: AdCampaignReportChannelBreakdown[]
}

export interface AdCampaignReportTotals {
  delivered: number
  opened: number
  clicked: number
  unsubscribed: number
  reported: number
  consumedBudgetYen: number
}

export interface AdCampaignReportDailyPoint {
  date: string
  delivered: number
  opened: number
  clicked: number
  consumedBudgetYen: number
}

export interface AdCampaignReportChannelBreakdown {
  channelType: AdChannelType
  delivered: number
  opened: number
  clicked: number
}

// === Requests ===

/** キャンペーン作成リクエスト（backend {@code CreateCampaignRequest}） */
export interface CreateAdMessagingCampaignRequest {
  name: string
  totalBudgetYen: number
  startsAt: string
  endsAt: string
  scheduledTimezone: string
  /** NULL 時は週 3 件のデフォルト */
  frequencyCapOverride?: number | null
}

/** キャンペーン更新リクエスト（backend {@code UpdateCampaignRequest}） */
export interface UpdateAdMessagingCampaignRequest {
  name: string
  totalBudgetYen: number
  startsAt: string
  endsAt: string
  scheduledTimezone: string
  frequencyCapOverride?: number | null
}

/** チャネル作成・更新リクエスト（backend {@code CampaignChannelRequest}） */
export interface AdMessagingCampaignChannelRequest {
  channelType: AdChannelType
  locale: string
  subject?: string | null
  bodyMarkdown: string
  imageUrl?: string | null
  ctaLabel?: string | null
  ctaUrl?: string | null
  /** BANNER 時のみ必須 */
  bannerCreativeId?: number | null
}

/** ターゲティング個別リクエスト（backend {@code AudienceSegmentRequest}） */
export interface AdMessagingCampaignAudienceSegmentRequest {
  segmentType: AdSegmentType
  segmentValue: AdSegmentValue
  inclusionMode: AdSegmentInclusionMode
}

/** ターゲティング全件 replace リクエスト（backend {@code AudienceConfigRequest}） */
export interface AdMessagingCampaignAudienceConfigRequest {
  segments: AdMessagingCampaignAudienceSegmentRequest[]
}

// === List parameters ===

export interface AdMessagingCampaignListParams {
  organizationId: number
  status?: AdMessagingCampaignStatus
  page?: number
  size?: number
  sort?: string
}
