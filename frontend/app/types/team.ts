import type { EstablishedDatePrecision, ProfileVisibility } from './organization'
export type { EstablishedDatePrecision, ProfileVisibility }

export type TeamTemplate =
  | 'CLUB'
  | 'CLINIC'
  | 'CLASS'
  | 'COMMUNITY'
  | 'COMPANY'
  | 'FAMILY'
  | 'RESTAURANT'
  | 'BEAUTY'
  | 'STORE'
  | 'VOLUNTEER'
  | 'NEIGHBORHOOD'
  | 'CONDO'
  | 'OTHER'
export type TeamVisibility = 'PUBLIC' | 'GUESTS_AND_ABOVE' | 'SUPPORTERS_AND_ABOVE' | 'MEMBERS_AND_ABOVE'

// Wave 3-B: TeamResponse ネスト構造（BE側変更に対応）
export interface TeamBasicInfoDto {
  name?: string
  nameKana?: string | null
  nickname1?: string | null
  nickname2?: string | null
}

export interface TeamLocationDto {
  template?: string
  prefecture?: string | null
  city?: string | null
  /**
   * 都道府県コード（JIS X 0401・2 桁、null 許容）。
   * BE `TeamResponse.TeamLocationDto.prefectureCode`（Jackson 既定 camelCase）と 1:1。
   */
  prefectureCode?: string | null
  /**
   * 市区町村コード（JIS X 0402・5 桁、null 許容）。
   * BE `TeamResponse.TeamLocationDto.cityCode` と 1:1。
   */
  cityCode?: string | null
}

export interface TeamVisibilityDto {
  visibility?: TeamVisibility
  supporterEnabled?: boolean
}

export interface TeamMetadataDto {
  version?: number
  memberCount?: number
  iconUrl?: string | null
  bannerUrl?: string | null
  /**
   * F15.4 Phase 5-β: Google Maps 埋め込み URL（管理画面表示用、null 許容）
   */
  mapEmbedUrl?: string | null
}

export interface TeamSocialDto {
  teamFriendCount?: number
  supporterCount?: number
}

export interface TeamTimestampsDto {
  archivedAt?: string | null
  createdAt?: string
}

export interface TeamResponse {
  /** UUID（public_id）。URLに使用する string 型。BE PR #1331 対応 */
  id: string
  basicInfo?: TeamBasicInfoDto
  location?: TeamLocationDto
  visibility?: TeamVisibilityDto
  metadata?: TeamMetadataDto
  social?: TeamSocialDto
  timestamps?: TeamTimestampsDto
}

export interface TeamSummaryResponse {
  id: number
  name: string
  nickname1: string | null
  iconUrl: string | null
  prefecture: string | null
  city: string | null
  template: TeamTemplate
  memberCount: number
  supporterEnabled: boolean
  teamFriendCount: number
  supporterCount: number
}

export interface CreateTeamRequest {
  name: string
  nameKana?: string
  nickname1?: string
  nickname2?: string
  template: TeamTemplate
  prefecture?: string
  city?: string
  /**
   * 都道府県コード（JIS X 0401・2 桁）。BE `CreateTeamRequest.prefectureCode`（camelCase）と 1:1。
   */
  prefectureCode?: string
  /**
   * 市区町村コード（JIS X 0402・5 桁）。BE `CreateTeamRequest.cityCode`（camelCase）と 1:1。
   */
  cityCode?: string
  description?: string
  visibility: TeamVisibility
  supporterEnabled: boolean
}

/**
 * F15.4 Phase 5-α: 未ログイン公開 API のレスポンス DTO
 *
 * `GET /api/v1/public/teams/{id}` で取得される抑制版 DTO。
 * メンバー一覧・連絡先・番地住所・supporterEnabled・archivedAt 等の
 * 内部状態は含めない（バックエンド `TeamPublicDetailResponse` と一致）。
 */
export interface TeamPublicDetailResponse {
  /** UUID（public_id）。URLに使用する string 型。BE PR #1331 対応 */
  id: string
  name: string
  nameKana: string | null
  nickname1: string | null
  nickname2: string | null
  template: TeamTemplate
  prefecture: string | null
  city: string | null
  iconUrl: string | null
  bannerUrl: string | null
  homepageUrl: string | null
  /** ISO 文字列（LocalDate を YYYY-MM-DD で受信） */
  establishedDate: string | null
  establishedDatePrecision: EstablishedDatePrecision | null
  philosophy: string | null
  memberCount: number | null
  mapEmbedUrl: string | null
}

export interface UpdateTeamRequest {
  name?: string
  nameKana?: string
  nickname1?: string
  nickname2?: string
  prefecture?: string
  city?: string
  /**
   * 都道府県コード（JIS X 0401・2 桁）。BE `UpdateTeamRequest.prefectureCode`（camelCase）と 1:1。
   * undefined（指定なし）は既存値を維持する。
   */
  prefectureCode?: string
  /**
   * 市区町村コード（JIS X 0402・5 桁）。BE `UpdateTeamRequest.cityCode`（camelCase）と 1:1。
   * undefined（指定なし）は既存値を維持する。
   */
  cityCode?: string
  description?: string
  visibility?: TeamVisibility
  supporterEnabled?: boolean
  /**
   * F15.4 Phase 5-β: Google Maps 埋め込み URL。
   * null を指定すると地図を削除、undefined（指定なし）は既存値を保持。
   * 形式: `^https://www\.google\.com/maps/embed\?...`
   */
  mapEmbedUrl?: string | null
}

// === F01.2 拡張プロフィール ===
// EstablishedDatePrecision と ProfileVisibility は organization.ts で定義（team/org 共通）

export interface TeamProfileResponse {
  id: number
  homepage_url: string | null
  established_date: string | null
  established_date_precision: EstablishedDatePrecision | null
  philosophy: string | null
  profile_visibility: ProfileVisibility | null
}

export interface UpdateTeamProfileRequest {
  homepage_url?: string | null
  established_date?: string | null
  established_date_precision?: EstablishedDatePrecision | null
  philosophy?: string | null
  profile_visibility?: ProfileVisibility | null
}

export interface TeamOfficerResponse {
  id: number
  team_id: number
  name: string
  title: string
  display_order: number
  is_visible: boolean
  is_publicly_visible: boolean | null
}

export interface CreateTeamOfficerRequest {
  name: string
  title: string
  is_visible?: boolean
}

export interface UpdateTeamOfficerRequest {
  name?: string
  title?: string
  is_visible?: boolean
}

export interface TeamCustomFieldResponse {
  id: number
  team_id: number
  label: string
  value: string
  display_order: number
  is_visible: boolean
  is_publicly_visible: boolean | null
}

export interface CreateTeamCustomFieldRequest {
  label: string
  value: string
  is_visible?: boolean
}

export interface UpdateTeamCustomFieldRequest {
  label?: string
  value?: string
  is_visible?: boolean
}
