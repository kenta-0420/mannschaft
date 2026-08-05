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

  /**
   * Date と時刻文字列（"HH:mm"）を組み合わせ、ユーザーTZオフセット付き OffsetDateTime 文字列を返す。
   * BE は OffsetDateTime として受け付けるため、TZオフセットを必ず付与する必要がある。
   *
   * - time が "HH:mm" 形式文字列の場合: その時刻をユーザーTZとして解釈し送信する（予定の startAt/endAt）
   * - time が '' の場合: 00:00:00 として解釈する（終日イベントの開始/終了）
   * - time が undefined（省略）の場合: date の時刻部分をそのままユーザーTZで変換する（絶対リマインダー・予約系）
   * - date が null の場合は null を返す
   *
   * @example buildOffsetDateTimeStr(new Date('2026-06-05'), '09:00') → "2026-06-05T09:00:00+09:00"
   * @example buildOffsetDateTimeStr(new Date('2026-06-05T09:30:00'), undefined) → "2026-06-05T09:30:00+09:00"
   */
  function buildOffsetDateTimeStr(date: Date | null, time?: string): string | null {
    if (!date) return null
    if (time === undefined) {
      // time 省略: date オブジェクト自身の時刻をユーザーTZで変換（絶対リマインダー等）
      return dayjs(date).tz(userTimezone.value).format()
    }
    // time 指定: 日付 + 指定時刻をユーザーTZとして解釈（startAt/endAt 等）
    const dateStr = dayjs(date).tz(userTimezone.value).format('YYYY-MM-DD')
    const timeStr = time ? `${time}:00` : '00:00:00'
    return dayjs.tz(`${dateStr}T${timeStr}`, userTimezone.value).format()
  }

  /**
   * `yyyy-MM-dd` 形式の暦日を「ユーザーTZでのその日の 00:00:00」として解釈し、
   * オフセット付き ISO-8601 文字列を返す（範囲検索の from 用）。
   *
   * カレンダーの月境界・週グリッド境界のように、暦日が先に決まっている用途で使う。
   * `Date` から組み立てる場合は {@link buildOffsetDateTimeStr} を使うこと。
   *
   * @example buildDayStartStr('2026-08-01') // JST → "2026-08-01T00:00:00+09:00"
   */
  function buildDayStartStr(ymd: string): string {
    return dayjs.tz(`${ymd}T00:00:00`, userTimezone.value).format()
  }

  /**
   * `yyyy-MM-dd` 形式の暦日を「ユーザーTZでのその日の 23:59:59」として解釈し、
   * オフセット付き ISO-8601 文字列を返す（範囲検索の to 用。BE の範囲検索は両端 inclusive）。
   *
   * @example buildDayEndStr('2026-08-31') // JST → "2026-08-31T23:59:59+09:00"
   */
  function buildDayEndStr(ymd: string): string {
    return dayjs.tz(`${ymd}T23:59:59`, userTimezone.value).format()
  }

  return {
    userTimezone,
    formatDate,
    formatDateTime,
    formatTime,
    fromNow,
    buildOffsetDateTimeStr,
    buildDayStartStr,
    buildDayEndStr,
  }
}
