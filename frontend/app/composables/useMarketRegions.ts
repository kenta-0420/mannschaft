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
  const { locale } = useI18n()

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

  // 都道府県一覧をどのロケールで取得済みか（ロケール切替時に再取得するためのキャッシュキー）
  const prefecturesLoadedLocale = ref<string | null>(null)

  /**
   * 都道府県一覧を取得する。
   * 同一ロケールでキャッシュ済みなら API 呼び出しをスキップし、ロケールが変わったら再取得する
   * （地域名表示を言語切替に追従させるため）。
   */
  async function loadPrefectures() {
    if (prefectures.value.length > 0 && prefecturesLoadedLocale.value === locale.value) return
    prefecturesLoading.value = true
    try {
      const res = await marketApi.listMarketRegions(undefined, locale.value)
      prefectures.value = res.data
      prefecturesLoadedLocale.value = locale.value
    }
    finally {
      prefecturesLoading.value = false
    }
  }

  /**
   * 指定都道府県の市区町村一覧を取得する（現在ロケールの地域名で取得）
   */
  async function loadCities(prefectureCode: string) {
    citiesLoading.value = true
    cities.value = []
    try {
      const res = await marketApi.listMarketRegions(prefectureCode, locale.value)
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

  // ロケール切替時に地域名表示を追従させる。
  // 取得済みの都道府県・選択中市区町村を現在ロケールで取り直す。
  watch(locale, async () => {
    if (prefectures.value.length > 0) {
      prefecturesLoadedLocale.value = null
      await loadPrefectures()
    }
    if (selectedPrefecture.value) {
      await loadCities(selectedPrefecture.value)
    }
  })

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
