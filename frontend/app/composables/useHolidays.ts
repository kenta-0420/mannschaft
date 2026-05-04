import Holidays from 'date-holidays'

/**
 * 祝日判定 composable。
 * ユーザーが設定した countryCode（ISO 3166-1 alpha-2）を使用して
 * 各国の祝日を判定する。未設定の場合は日本（JP）をデフォルトとする。
 */
export function useHolidays() {
  const { getProfile } = useUserSettingsApi()

  // ユーザーの countryCode をキャッシュ（初回取得後は再フェッチしない）
  const userCountryCode = ref<string | null | undefined>(undefined)

  // 未取得の場合のみプロフィールAPIを呼び出す
  if (import.meta.client && userCountryCode.value === undefined) {
    getProfile()
      .then((res) => {
        userCountryCode.value = res.data.countryCode ?? null
      })
      .catch(() => {
        userCountryCode.value = null
      })
  }

  const countryCode = computed<string>(() => {
    const code = userCountryCode.value
    if (code && /^[A-Z]{2}$/.test(code)) return code
    // 未設定・無効値の場合は日本をデフォルトとする
    return 'JP'
  })

  /**
   * 指定した日付が祝日であれば祝日名を返す。祝日でなければ null を返す。
   * @param dateStr - "YYYY-MM-DD" 形式の日付文字列
   */
  function getHoliday(dateStr: string): string | null {
    const parts = dateStr.split('-').map(Number)
    const year = parts[0] ?? 0
    const month = parts[1] ?? 1
    const day = parts[2] ?? 1
    // タイムゾーン問題を避けるためローカル日付で生成する
    const date = new Date(year, month - 1, day)
    const hd = new Holidays(countryCode.value)
    const results = hd.isHoliday(date)
    if (!results) return null
    // public（法定祝日）を優先し、なければ最初の結果を使用する
    const pub = results.find((h) => h.type === 'public')
    return pub ? pub.name : results[0]?.name ?? null
  }

  return { getHoliday, countryCode }
}
