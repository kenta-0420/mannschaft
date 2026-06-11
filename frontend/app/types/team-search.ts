/**
 * F15.4 組織内チーム（店舗）検索 — 型定義
 *
 * 設計書: docs/features/F15.4_team_store_search_within_org.md §3 (API), §5 (フロント)
 *
 * 重要な型上の特徴:
 * - レスポンス DTO は `TeamPublicSummary` と `TeamSearchResult` の union 型
 *   （バックエンドが閲覧者の権限に応じて出し分ける。非メンバーは抑制版、組織メンバーは詳細版）
 * - `isTeamSearchResult` タイプガードで実体を判定する
 */

/** 検索クエリパラメータ */
export interface TeamSearchQuery {
  /** 名前 / かな / nickname1 への部分一致キーワード */
  keyword?: string
  /**
   * 都道府県名称（完全一致・後方互換フォールバック）。
   * F22.1 Phase2 足場C 第三陣以降はコード（{@link prefectureCode}）送信を優先する。
   */
  prefecture?: string
  /**
   * 市区町村名称（完全一致・後方互換フォールバック）。
   * F22.1 Phase2 足場C 第三陣以降はコード（{@link cityCode}）送信を優先する。
   */
  city?: string
  /**
   * 都道府県コード（JIS X 0401・2 桁）。
   * BE `OrganizationTeamSearchController` の `@RequestParam prefectureCode`（camelCase）と 1:1。
   * 指定時は BE 側で名称より優先される（dual-support）。
   */
  prefectureCode?: string
  /**
   * 市区町村コード（JIS X 0402・5 桁）。
   * BE `@RequestParam cityCode`（camelCase）と 1:1。指定時は名称より優先（dual-support）。
   */
  cityCode?: string
  /** チームテンプレート（完全一致） */
  template?: string
  /** ページ番号（0 オリジン） */
  page?: number
  /** ページサイズ */
  size?: number
  /** ソート順 */
  sort?: TeamSearchSort
}

/** ソート順の許可値 */
export type TeamSearchSort = 'nameKana,asc' | 'name,asc' | 'createdAt,desc'

/**
 * 未ログイン者・非メンバー向けの抑制版 DTO。
 * 個人情報や運営詳細は含めず、店舗一覧として閲覧可能な最小限の項目のみ返す。
 */
export interface TeamPublicSummary {
  id: number
  name: string
  nameKana: string
  prefecture: string | null
  city: string | null
  /**
   * 都道府県コード（JIS X 0401・2 桁、null 許容）。
   * BE `TeamPublicSummaryResponse.prefectureCode`（Jackson 既定 camelCase）と 1:1。
   */
  prefectureCode: string | null
  /**
   * 市区町村コード（JIS X 0402・5 桁、null 許容）。
   * BE `TeamPublicSummaryResponse.cityCode` と 1:1。
   */
  cityCode: string | null
  template: string | null
  iconUrl: string | null
}

/**
 * 組織メンバー向けの詳細版 DTO。
 * `TeamPublicSummary` を拡張し、可視性・バナー画像・サポーター機能の有無を含む。
 */
export interface TeamSearchResult extends TeamPublicSummary {
  visibility: 'PUBLIC' | 'GUESTS_AND_ABOVE' | 'SUPPORTERS_AND_ABOVE' | 'MEMBERS_AND_ABOVE'
  bannerUrl: string | null
  supporterEnabled: boolean
}

/**
 * 検索 API のレスポンス要素。
 * バックエンドが閲覧者の権限に応じて `TeamPublicSummary` か `TeamSearchResult` を返す。
 */
export type TeamSearchItem = TeamPublicSummary | TeamSearchResult

/**
 * 詳細版 (`TeamSearchResult`) であるかを判定するタイプガード。
 * `visibility` フィールドの有無で判定する。
 */
export function isTeamSearchResult(item: TeamSearchItem): item is TeamSearchResult {
  return 'visibility' in item
}

/**
 * 指定 ID の組織が存在しないことを表す専用エラー。
 * `searchOrganizationTeams` が 404 応答を受け取った際にスローされる。
 */
export class OrganizationNotFoundError extends Error {
  readonly organizationId: number | string

  constructor(organizationId: number | string) {
    super(`Organization not found: ${organizationId}`)
    this.name = 'OrganizationNotFoundError'
    this.organizationId = organizationId
  }
}

/**
 * 検索 API のレート制限に達したことを表す専用エラー。
 * `searchOrganizationTeams` が 429 応答を受け取った際にスローされる。
 */
export class TeamSearchRateLimitError extends Error {
  /** Retry-After ヘッダ（秒）の値（存在しない場合は null） */
  readonly retryAfterSeconds: number | null

  constructor(retryAfterSeconds: number | null = null) {
    super('Team search rate limit exceeded')
    this.name = 'TeamSearchRateLimitError'
    this.retryAfterSeconds = retryAfterSeconds
  }
}
