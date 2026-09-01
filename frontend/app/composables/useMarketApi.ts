import type {
  MarketListingPage,
  MarketListingResponse,
  MarketListingsParams,
  MarketRegion,
  MarketSummary,
} from '~/types/market'
import type { RecruitmentCategoryResponse } from '~/types/recruitment'

interface ApiResponse<T> {
  data: T
}

/**
 * F22.1 市（Market）— 公開API クライアント
 *
 * 担当エンドポイント（すべて permitAll・未ログイン可）:
 *   - GET /api/v1/public/market/listings    : 市の札一覧（PagedResponse 形: data + meta）
 *   - GET /api/v1/public/market/listings/{id}: 公開札詳細（ApiResponse 形: data）
 *   - GET /api/v1/public/market/regions     : 都道府県 / 市区町村一覧（ApiResponse 形: data 配列）
 *   - GET /api/v1/public/market/summary     : 地域別件数サマリー（ApiResponse 形: data）
 *   - GET /api/v1/public/market/categories  : ジャンルマスタ一覧（ApiResponse 形: data 配列）
 *
 * ⚠️ クエリパラメータ名は BE の @RequestParam に一致させること（MarketController）:
 *   prefecture / city / category_id / owner_type / keyword / include_region_none / sort / page / size
 *
 * 設計書: docs/features/F22.1_market/02_api_design.md §3
 */
export function useMarketApi() {
  const api = useApi()

  // ===========================================
  // 市の札一覧（PagedResponse 形: { data: [...], meta: {...} }）
  // ===========================================

  async function listMarketListings(params?: MarketListingsParams) {
    const q = new URLSearchParams()
    if (params?.prefecture) q.set('prefecture', params.prefecture)
    if (params?.city) q.set('city', params.city)
    if (params?.categoryId != null) q.set('category_id', String(params.categoryId))
    if (params?.ownerType) q.set('owner_type', params.ownerType)
    if (params?.keyword) q.set('keyword', params.keyword)
    if (params?.includeRegionNone != null) {
      q.set('include_region_none', String(params.includeRegionNone))
    }
    if (params?.sort) q.set('sort', params.sort)
    if (params?.page != null) q.set('page', String(params.page))
    if (params?.size != null) q.set('size', String(params.size))
    if (params?.lang) q.set('lang', params.lang)
    const suffix = q.toString() ? `?${q.toString()}` : ''
    // 一覧は BE PagedResponse（data 配列 + meta）をそのまま受け取る。
    return api<MarketListingPage>(
      `/api/v1/public/market/listings${suffix}`,
    )
  }

  // ===========================================
  // 公開札詳細
  // ===========================================

  async function getMarketListing(id: number, lang?: string) {
    const suffix = lang ? `?lang=${encodeURIComponent(lang)}` : ''
    return api<ApiResponse<MarketListingResponse>>(
      `/api/v1/public/market/listings/${id}${suffix}`,
    )
  }

  /** 認証済み利用者が札を通報する。scope/owner/snapshot はサーバー側で導出する。 */
  async function reportMarketListing(id: number, reason: string, description?: string) {
    return api<ApiResponse<unknown>>('/api/v1/reports', {
      method: 'POST',
      body: {
        targetType: 'RECRUITMENT_LISTING',
        targetId: id,
        reason,
        description: description?.trim() || null,
      },
    })
  }

  // ===========================================
  // 地域ファサード
  // ===========================================

  /**
   * 都道府県一覧（prefecture 省略時）または
   * 指定都道府県の市区町村一覧（prefecture 指定時）を返す。
   *
   * @param prefecture 都道府県コード（市区町村一覧取得時に指定）
   * @param lang       表示言語（地域名の多言語表示。未訳は BE 側で日本語フォールバック）
   */
  async function listMarketRegions(prefecture?: string, lang?: string) {
    const q = new URLSearchParams()
    if (prefecture) q.set('prefecture', prefecture)
    if (lang) q.set('lang', lang)
    const suffix = q.toString() ? `?${q.toString()}` : ''
    return api<ApiResponse<MarketRegion[]>>(
      `/api/v1/public/market/regions${suffix}`,
    )
  }

  // ===========================================
  // 地域別件数サマリー
  // ===========================================

  async function getMarketSummary(lang?: string) {
    const suffix = lang ? `?lang=${encodeURIComponent(lang)}` : ''
    return api<ApiResponse<MarketSummary>>(
      `/api/v1/public/market/summary${suffix}`,
    )
  }

  // ===========================================
  // ジャンル（カテゴリ）マスタ一覧
  // ===========================================

  /**
   * 市のジャンルフィルタ用カテゴリマスタを取得する。
   *
   * ⚠️ 旧実装は recruitment の認証必須 API（/api/v1/recruitment-categories）を直叩きしており、
   * 未ログインでは 401 → useApi の onResponseError が市ページごと /login へリダイレクトする
   * 重大バグの原因となっていた。本関数は permitAll の公開エンドポイントを使う。
   */
  async function listMarketCategories() {
    return api<ApiResponse<RecruitmentCategoryResponse[]>>(
      `/api/v1/public/market/categories`,
    )
  }

  return {
    listMarketListings,
    getMarketListing,
    reportMarketListing,
    listMarketRegions,
    getMarketSummary,
    listMarketCategories,
  }
}
