/**
 * F22.1 市（Market）の型定義 — 手動管理型
 *
 * - MarketListingResponse: 公開API `/public/market/listings` のレスポンス
 * - MarketRegion: 地域ノード（都道府県 / 市区町村）
 * - MarketSummary: パンくず/集客用の地域別件数
 * - FriendTargetInput: 非公開札の宛先（discriminated union・3粒度）
 *
 * 設計書: docs/features/F22.1_market/02_api_design.md §3 / §4
 */

// ===========================================
// 可視性 / ステータス
// ===========================================

export type MarketVisibility = 'PUBLIC' | 'FRIEND_TEAMS_ONLY' | 'SCOPE_ONLY'

export type MarketListingStatus = 'OPEN' | 'FULL' | 'COMPLETED' | 'CANCELLED' | 'AUTO_CANCELLED'

// ===========================================
// 地域
// ===========================================

/**
 * 地域ノード（都道府県一覧 / 市区町村一覧共通）
 * GET /api/v1/public/market/regions のレスポンス要素
 */
export interface MarketRegion {
  code: string
  name: string
  prefecture_code: string
}

/**
 * 地域サマリー（パンくず / 件数バッジ用）
 * GET /api/v1/public/market/summary
 */
export interface MarketRegionSummaryEntry {
  code: string
  name: string
  count: number
}

export interface MarketSummary {
  by_prefecture: MarketRegionSummaryEntry[]
  by_city: MarketRegionSummaryEntry[]
}

// ===========================================
// 公開市 API レスポンス
// ===========================================

/**
 * 市の札（公開リスト）の主催者情報（PII抑制: 公称名+アイコンのみ）
 */
export interface MarketOwner {
  scope_type: 'TEAM' | 'ORGANIZATION'
  scope_id: number
  display_name: string
  icon_url: string | null
}

/**
 * 市の札に付随する地域情報
 */
export interface MarketListingRegion {
  prefecture_code: string
  prefecture_name: string
  city_code: string
  city_name: string
}

/**
 * 市の札カテゴリ（i18nキー付き）
 */
export interface MarketCategory {
  id: number
  name_key: string
}

/**
 * 市の公開札レスポンス（PII抑制・未ログイン可）
 * GET /api/v1/public/market/listings / GET /api/v1/public/market/listings/{id}
 */
export interface MarketListingResponse {
  id: number
  title: string
  category: MarketCategory
  owner: MarketOwner
  region: MarketListingRegion | null
  location_text: string | null
  start_at: string
  application_deadline: string
  capacity: number
  confirmed_count: number
  status: MarketListingStatus
  payment_enabled: boolean
}

/**
 * 市の公開札一覧レスポンス（ページング）
 */
export interface MarketListingPage {
  content: MarketListingResponse[]
  total_elements: number
  page: number
  size: number
}

// ===========================================
// 検索クエリパラメータ
// ===========================================

export interface MarketListingsParams {
  prefecture?: string
  city?: string
  category_id?: number
  keyword?: string
  include_region_none?: boolean
  page?: number
  size?: number
}

// ===========================================
// 非公開札の宛先セレクタ（discriminated union）
// 設計書: docs/features/F22.1_market/02_api_design.md §4 / §9
// ===========================================

/**
 * 全成立フレンド宛
 */
export interface FriendTargetAllFriends {
  target_kind: 'ALL_FRIENDS'
}

/**
 * フォルダ指定宛
 */
export interface FriendTargetFolder {
  target_kind: 'FOLDER'
  folder_id: number
}

/**
 * 個別チーム宛（成立フレンドのみ）
 */
export interface FriendTargetTeam {
  target_kind: 'TEAM'
  team_id: number
}

/**
 * フレンド宛先（3粒度の混在可・discriminated union で型安全に表現）
 * `any` 禁止・複雑型パズル禁止（CLAUDE.md）
 */
export type FriendTargetInput =
  | FriendTargetAllFriends
  | FriendTargetFolder
  | FriendTargetTeam

// ===========================================
// 札立てリクエスト拡張（既存 CreateRecruitmentListingRequest への追加項目）
// ===========================================

/**
 * 既存 recruitment 作成 API に追加する市向けフィールド
 */
export interface MarketListingExtension {
  prefecture_code?: string
  city_code?: string
  friend_targets?: FriendTargetInput[]
}
