import type { MarketRegion } from '~/types/market'

/**
 * F22.1 市（Market）— 地域連動 composable
 *
 * 都道府県を選択すると配下の市区町村を動的ロードする。
 * フィルタバーの都道府県 Select → 市区町村 Select の連動に使用する。
 *
 * 設計書: docs/features/F22.1_market/03_ui_i18n.md §2.1
 */
export function useMarketRegions() {
  const marketApi = useMarketApi()

  // 都道府県一覧（初回取得後にキャッシュ）
  const prefectures = ref<MarketRegion[]>([])
  const prefecturesLoading = ref(false)

  // 市区町村一覧（都道府県選択後に動的ロード）
  const cities = ref<MarketRegion[]>([])
  const citiesLoading = ref(false)

  // 選択中の都道府県コード
  const selectedPrefecture = ref<string | null>(null)

  // 選択中の市区町村コード
  const selectedCity = ref<string | null>(null)

  /**
   * 都道府県一覧を取得する（初回のみ API 呼び出し）
   */
  async function loadPrefectures() {
    if (prefectures.value.length > 0) return
    prefecturesLoading.value = true
    try {
      const res = await marketApi.listMarketRegions()
      prefectures.value = res.data
    }
    finally {
      prefecturesLoading.value = false
    }
  }

  /**
   * 指定都道府県の市区町村一覧を取得する
   */
  async function loadCities(prefectureCode: string) {
    citiesLoading.value = true
    cities.value = []
    try {
      const res = await marketApi.listMarketRegions(prefectureCode)
      cities.value = res.data
    }
    finally {
      citiesLoading.value = false
    }
  }

  /**
   * 都道府県選択時に呼び出す。
   * 市区町村選択をリセットし、配下の市区町村を動的ロードする。
   */
  async function selectPrefecture(code: string | null) {
    selectedPrefecture.value = code
    selectedCity.value = null
    cities.value = []
    if (code) {
      await loadCities(code)
    }
  }

  /**
   * フィルタをすべてリセットする
   */
  function resetRegions() {
    selectedPrefecture.value = null
    selectedCity.value = null
    cities.value = []
  }

  return {
    prefectures,
    prefecturesLoading,
    cities,
    citiesLoading,
    selectedPrefecture,
    selectedCity,
    loadPrefectures,
    loadCities,
    selectPrefecture,
    resetRegions,
  }
}
