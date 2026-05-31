import type {
  MarketListingPage,
  MarketListingResponse,
  MarketListingsParams,
  MarketRegion,
  MarketSummary,
} from '~/types/market'

interface ApiResponse<T> {
  data: T
}

/**
 * F22.1 市（Market）— 公開API クライアント
 *
 * 担当エンドポイント（すべて permitAll・未ログイン可）:
 *   - GET /api/v1/public/market/listings    : 市の札一覧
 *   - GET /api/v1/public/market/listings/{id}: 公開札詳細
 *   - GET /api/v1/public/market/regions     : 都道府県 / 市区町村一覧
 *   - GET /api/v1/public/market/summary     : 地域別件数サマリー
 *
 * 設計書: docs/features/F22.1_market/02_api_design.md §3
 */
export function useMarketApi() {
  const api = useApi()

  // ===========================================
  // 市の札一覧
  // ===========================================

  async function listMarketListings(params?: MarketListingsParams) {
    const q = new URLSearchParams()
    if (params?.prefecture) q.set('prefecture', params.prefecture)
    if (params?.city) q.set('city', params.city)
    if (params?.category_id != null) q.set('category_id', String(params.category_id))
    if (params?.keyword) q.set('keyword', params.keyword)
    if (params?.include_region_none != null) {
      q.set('include_region_none', String(params.include_region_none))
    }
    if (params?.page != null) q.set('page', String(params.page))
    if (params?.size != null) q.set('size', String(params.size))
    const suffix = q.toString() ? `?${q.toString()}` : ''
    return api<ApiResponse<MarketListingPage>>(
      `/api/v1/public/market/listings${suffix}`,
    )
  }

  // ===========================================
  // 公開札詳細
  // ===========================================

  async function getMarketListing(id: number) {
    return api<ApiResponse<MarketListingResponse>>(
      `/api/v1/public/market/listings/${id}`,
    )
  }

  // ===========================================
  // 地域ファサード
  // ===========================================

  /**
   * 都道府県一覧（prefecture 省略時）または
   * 指定都道府県の市区町村一覧（prefecture 指定時）を返す。
   */
  async function listMarketRegions(prefecture?: string) {
    const suffix = prefecture ? `?prefecture=${encodeURIComponent(prefecture)}` : ''
    return api<ApiResponse<MarketRegion[]>>(
      `/api/v1/public/market/regions${suffix}`,
    )
  }

  // ===========================================
  // 地域別件数サマリー
  // ===========================================

  async function getMarketSummary() {
    return api<ApiResponse<MarketSummary>>(
      `/api/v1/public/market/summary`,
    )
  }

  return {
    listMarketListings,
    getMarketListing,
    listMarketRegions,
    getMarketSummary,
  }
}
