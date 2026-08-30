/**
 * F22.1 市（Market）の型定義 — 手動管理型
 *
 * - MarketListingResponse: 公開API `/public/market/listings` のレスポンス
 * - MarketRegion: 地域ノード（都道府県 / 市区町村）
 * - MarketSummary: パンくず/集客用の地域別件数
 * - FriendTargetInput: 非公開札の宛先（discriminated union・3粒度）
 *
 * ⚠️ 命名規約: バックエンド（Spring Boot 既定の Jackson = camelCase）の JSON 契約に
 * 1:1 で一致させること。BE 側の正典は以下:
 *   - backend/.../market/dto/*.java（MarketListingResponse / MarketOwnerDto / MarketRegionDto / MarketCategoryDto / MarketSummaryResponse / MarketRegionNodeResponse）
 *   - backend/.../common/PagedResponse.java（一覧の実体: { data: [...], meta: { total, page, size, totalPages } }）
 *   - backend/.../recruitment/dto/FriendTargetRequest.java（targetKind / folderId / teamId）
 *
 * 設計書: docs/features/F22.1_market/02_api_design.md §3 / §4
 */

// ===========================================
// 可視性 / ステータス
// ===========================================

export type MarketVisibility = 'PUBLIC' | 'FRIEND_TEAMS_ONLY' | 'SCOPE_ONLY' | 'SELECTED_SCOPES'

/** 個人札の所属先限定公開に使う、本人が現在所属するスコープ。 */
export interface MarketAudienceScope {
  scopeType: 'TEAM' | 'ORGANIZATION'
  scopeId: number
}

export type MarketListingStatus =
  | 'DRAFT'
  | 'OPEN'
  | 'FULL'
  | 'COMPLETED'
  | 'CANCELLED'
  | 'AUTO_CANCELLED'

/** Phase 3 個人市の最小マッチング表示。応募者の識別子は含めない。 */
export interface PersonalMarketMatch {
  participantId: number
  participantType: 'USER' | 'TEAM'
  status: import('~/types/recruitment').RecruitmentParticipantStatus
  waitlistPosition: number | null
  appliedAt: string
  statusChangedAt: string
}

export interface PersonalMarketListingsParams {
  status?: import('~/types/recruitment').RecruitmentListingStatus
  prefectureCode?: string
  cityCode?: string
  categoryId?: number
  page?: number
  size?: number
}

// ===========================================
// 地域
// ===========================================

/**
 * 地域ノード（都道府県一覧 / 市区町村一覧共通）
 * GET /api/v1/public/market/regions のレスポンス要素
 * BE: MarketRegionNodeResponse（code / name / prefectureCode）
 */
export interface MarketRegion {
  code: string
  name: string
  /** 親都道府県コード（市区町村ノードのみ。都道府県ノードでは null）。 */
  prefectureCode: string | null
}

/**
 * 地域サマリー（パンくず / 件数バッジ用）の地域ノード件数
 * BE: MarketSummaryResponse.RegionCount（code / name / count）
 */
export interface MarketRegionSummaryEntry {
  code: string
  name: string
  count: number
}

/**
 * 地域サマリー
 * GET /api/v1/public/market/summary
 * BE: MarketSummaryResponse（byPrefecture / byCity）
 */
export interface MarketSummary {
  byPrefecture: MarketRegionSummaryEntry[]
  byCity: MarketRegionSummaryEntry[]
}

// ===========================================
// 公開市 API レスポンス
// ===========================================

/**
 * 市の札（公開リスト）の主催者情報（PII抑制: 公称名+アイコンのみ）
 * BE: MarketOwnerDto（scopeType / scopeId / displayName / iconUrl）
 */
export interface MarketOwner {
  scopeType: 'PERSONAL' | 'TEAM' | 'ORGANIZATION'
  /** PERSONAL は公開DTOで主体IDを出さない。 */
  scopeId: number | null
  displayName: string
  iconUrl: string | null
}

/**
 * 市の札に付随する地域情報
 * BE: MarketRegionDto（prefectureCode / prefectureName / cityCode / cityName）
 */
export interface MarketListingRegion {
  prefectureCode: string
  prefectureName: string
  cityCode: string
  cityName: string
}

/**
 * 市の札カテゴリ（i18nキー付き）
 * BE: MarketCategoryDto（id / nameKey）
 */
export interface MarketCategory {
  id: number
  nameKey: string
}

/**
 * 市の公開札レスポンス（PII抑制・未ログイン可）
 * GET /api/v1/public/market/listings / GET /api/v1/public/market/listings/{id}
 * BE: MarketListingResponse
 */
export interface MarketListingResponse {
  id: number
  title: string
  category: MarketCategory
  owner: MarketOwner
  /** 代表地域（複数地域札の先頭。後方互換用・地域なしは null）。 */
  region: MarketListingRegion | null
  /**
   * 札に紐づく全地域（複数地域募集 N:N・F22.1 Phase2 D）。
   * 空配列は「地域を問わない札」を表す。BE: MarketListingResponse.regions（camelCase）。
   */
  regions: MarketListingRegion[]
  locationText: string | null
  startAt: string
  applicationDeadline: string
  capacity: number
  confirmedCount: number
  status: MarketListingStatus
  paymentEnabled: boolean
  /** 参加種別。INDIVIDUAL=個人応募（participantType: USER）/ TEAM=チーム応募（participantType: TEAM + teamId 必須）。 */
  participationType: 'INDIVIDUAL' | 'TEAM'
}

// ===========================================
// ページング（BE: common/PagedResponse）
// ===========================================

/**
 * BE PagedResponse のメタ情報（PageMeta）
 */
export interface PagedMeta {
  total: number
  page: number
  size: number
  totalPages: number
}

/**
 * BE PagedResponse 形（{ data: [...], meta: {...} }）
 * 一覧 API はこの形で返る（ApiResponse<List<T>> を継承し meta を持つ）。
 */
export interface PagedResponse<T> {
  data: T[]
  meta: PagedMeta
}

/**
 * 市の公開札一覧レスポンス
 */
export type MarketListingPage = PagedResponse<MarketListingResponse>

// ===========================================
// 検索クエリパラメータ
// ===========================================

export interface MarketListingsParams {
  prefecture?: string
  city?: string
  categoryId?: number
  keyword?: string
  includeRegionNone?: boolean
  page?: number
  size?: number
  /** 表示言語（札に付随する地域名の多言語表示。未訳は BE 側で日本語フォールバック）。 */
  lang?: string
}

// ===========================================
// 非公開札の宛先セレクタ（discriminated union）
// BE: FriendTargetRequest（targetKind / folderId / teamId）
// 設計書: docs/features/F22.1_market/02_api_design.md §4 / §9
// ===========================================

/**
 * 全成立フレンド宛
 */
export interface FriendTargetAllFriends {
  targetKind: 'ALL_FRIENDS'
}

/**
 * フォルダ指定宛
 */
export interface FriendTargetFolder {
  targetKind: 'FOLDER'
  folderId: number
}

/**
 * 個別チーム宛（成立フレンドのみ）
 */
export interface FriendTargetTeam {
  targetKind: 'TEAM'
  teamId: number
}

/**
 * フレンド宛先（3粒度の混在可・discriminated union で型安全に表現）
 * `any` 禁止・複雑型パズル禁止（CLAUDE.md）
 */
export type FriendTargetInput = FriendTargetAllFriends | FriendTargetFolder | FriendTargetTeam

// ===========================================
// 札立てリクエスト拡張（既存 CreateRecruitmentListingRequest への追加項目）
// BE: CreateRecruitmentListingRequest（prefectureCode / cityCode / friendTargets）
// ===========================================

/**
 * 複数地域募集（N:N・F22.1 Phase2 D）の地域ペア入力。
 * BE: CreateRecruitmentListingRequest.RegionInput（prefectureCode / cityCode）。
 * 県単位は cityCode を null にする。
 */
export interface RegionInput {
  prefectureCode: string
  cityCode: string | null
}

/**
 * 既存 recruitment 作成 API に追加する市向けフィールド
 */
export interface MarketListingExtension {
  prefectureCode?: string
  cityCode?: string
  /** 複数地域募集（N:N・F22.1 Phase2 D）。指定時は BE が中間表へ全置換する。 */
  regions?: RegionInput[]
  friendTargets?: FriendTargetInput[]
}
