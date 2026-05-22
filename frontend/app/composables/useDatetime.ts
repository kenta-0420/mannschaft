/**
 * タイムゾーン対応の日時フォーマット composable。
 *
 * バックエンドが返す ISO-8601 文字列（例: "2026-05-22T09:15:20+09:00"）を
 * ユーザーのタイムゾーン設定に基づいて正しく表示する。
 *
 * タイムゾーンは useAuthStore の user.timezone から取得し、
 * 未設定の場合は 'Asia/Tokyo' をデフォルトとして使用する。
 */
import dayjs from 'dayjs'

export function useDatetime() {
  const authStore = useAuthStore()
  const userTimezone = computed(() => authStore.user?.timezone ?? 'Asia/Tokyo')

  /**
   * ISO-8601 文字列を安全にパースする。
   * null / undefined / 空文字列 / 不正な値の場合は null を返す。
   */
  function safeParse(iso: string | null | undefined): dayjs.Dayjs | null {
    if (!iso) return null
    const d = dayjs(iso)
    return d.isValid() ? d : null
  }

  /**
   * 日付のみを表示する。
   * @example formatDate("2026-05-22T09:15:20+09:00") → "2026/05/22"
   */
  function formatDate(iso: string | null | undefined): string {
    const d = safeParse(iso)
    if (!d) return ''
    return d.tz(userTimezone.value).format('YYYY/MM/DD')
  }

  /**
   * 日付と時刻を表示する。
   * @example formatDateTime("2026-05-22T09:15:20+09:00") → "2026/05/22 09:15"
   */
  function formatDateTime(iso: string | null | undefined): string {
    const d = safeParse(iso)
    if (!d) return ''
    return d.tz(userTimezone.value).format('YYYY/MM/DD HH:mm')
  }

  /**
   * 時刻のみを表示する。
   * @example formatTime("2026-05-22T09:15:20+09:00") → "09:15"
   */
  function formatTime(iso: string | null | undefined): string {
    const d = safeParse(iso)
    if (!d) return ''
    return d.tz(userTimezone.value).format('HH:mm')
  }

  /**
   * 現在時刻からの相対表示を返す（日本語）。
   * @example fromNow("2026-05-22T06:15:20+09:00") → "3時間前"
   */
  function fromNow(iso: string | null | undefined): string {
    const d = safeParse(iso)
    if (!d) return ''
    return d.fromNow()
  }

  return {
    userTimezone,
    formatDate,
    formatDateTime,
    formatTime,
    fromNow,
  }
}
