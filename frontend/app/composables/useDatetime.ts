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
import { toLocalDateString } from '~/utils/localDate'

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
   * - time が undefined（省略）の場合: date の**壁時計**の時分秒をユーザーTZとして解釈する
   *   （絶対リマインダー・予約系）
   * - date が null の場合は null を返す
   *
   * ## なぜ「壁時計成分の取り出し」なのか（Issue #2508 Phase 2）
   *
   * DatePicker / 時刻入力が v-model で返す `Date` は **「瞬間(instant)」ではなく
   * 「ユーザーが画面で指した壁時計」** である。ピッカーはブラウザTZでカレンダーを描画するため、
   * ユーザーがクリックしたセルの年月日時分は `Date` のローカル壁時計成分そのものだからだ。
   *
   * これを `dayjs(date).tz(userTimezone)` で「瞬間」としてプロフィールTZへ投影し直すと、
   * ブラウザTZとプロフィールTZが食い違うユーザー（出張中など）で**選んだ日が1日ずれる**:
   *
   * ```
   * ブラウザ JST で 8/4 00:00 を選択 → その瞬間は America/Los_Angeles では 8/3 08:00
   *   → 旧実装は "2026-08-03..." を送信していた（ユーザーは 8/4 を選んだのに）
   * ```
   *
   * よって本関数は `getFullYear()` / `getMonth()` / `getDate()`（time 省略時は
   * `getHours()` / `getMinutes()` / `getSeconds()` も）でローカル壁時計成分を取り出し、
   * それを `dayjs.tz(壁時計文字列, userTimezone)` でユーザーTZの壁時計として解釈する。
   * これにより「画面で見た値がそのまま送られる」（WYSIWYG）が保証される。
   * 同じ原則は {@link ~/utils/localDate#toLocalDateString}（`LocalDate` 用）にも記してある。
   *
   * ⚠️ 意味論の変更: 旧実装の `time === undefined` 分岐は「瞬間の保存」だったが、
   * 本実装では「壁時計の保存」になる。ブラウザTZ = プロフィールTZ のとき（大多数）は結果が同一で、
   * 食い違うときのみ「ユーザーが見た時刻」が優先される。ピッカー由来の値しか渡らないため妥当である。
   *
   * @example buildOffsetDateTimeStr(new Date(2026, 5, 5), '09:00') → "2026-06-05T09:00:00+09:00"
   * @example buildOffsetDateTimeStr(new Date(2026, 5, 5, 9, 30)) → "2026-06-05T09:30:00+09:00"
   */
  function buildOffsetDateTimeStr(date: Date | null, time?: string): string | null {
    if (!date) return null
    // 年月日は常にブラウザのローカル壁時計成分から取り出す（投影し直すと1日ずれる）
    const dateStr = toLocalDateString(date)
    const timeStr
      = time === undefined
        // time 省略: date 自身の壁時計の時分秒を採用（絶対リマインダー等）
        ? [date.getHours(), date.getMinutes(), date.getSeconds()]
            .map(n => String(n).padStart(2, '0'))
            .join(':')
        // time 指定: 指定時刻を採用（'' は終日イベントの 00:00:00）
        : time ? `${time}:00` : '00:00:00'
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
