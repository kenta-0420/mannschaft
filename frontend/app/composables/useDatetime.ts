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
   * `<input type="datetime-local">` の値（`YYYY-MM-DDTHH:mm` または `YYYY-MM-DDTHH:mm:ss`）を、
   * **文字列の壁時計成分のまま**ユーザーTZで解釈し、オフセット付き ISO-8601 文字列を返す。
   *
   * `new Date(value)` を経由してはならない。ブラウザTZで存在しない時刻（DST 開始時刻。例:
   * America/New_York の 2026-03-08 02:30）が 1 時間ずれて正規化され、ずれた壁時計にユーザーTZの
   * オフセットを付けて送ってしまう（2026-09-24 検分指摘）。`dayjs.tz(...).format()` も内部で
   * ブラウザローカルの Date を経由するため同じくずれる（実測: 03:30 になった）。
   * そこで壁時計の文字列はそのまま使い、オフセットだけを `Intl.DateTimeFormat`（ユーザーTZ）から求める。
   *
   * ## ユーザーTZ自身の DST 境界（2026-09-24 検分指摘 P2）
   *
   * ブラウザTZだけでなく、**ユーザーの プロフィールTZ** 自身が DST を持つ場合にも罠がある。例えば
   * ユーザーTZ=America/New_York で `2026-03-08T02:30`（春・夏時間開始で 02:00→03:00 に飛ぶため
   * 02:30 という壁時計が実在しない）を渡すと、素朴な変換は「一番近いオフセット」を機械的に付けて
   * 存在しない瞬間をでっち上げてしまい、再表示すると 01:30 にずれる。これを黙って送らないよう、
   * 本関数は **候補オフセットで実際にユーザーTZ上の壁時計を再現できるか検証**し、どの候補でも
   * 再現できない（＝存在しない壁時計）場合は `null` を返す。呼び出し側はこれをバリデーションエラー
   * として扱い、送信してはならない。
   *
   * 逆に秋・夏時間終了（フォールバック）では同じ壁時計が2回現れる（例: America/New_York の
   * 2026-11-01 01:30 は夏時間側→標準時側の順で2回訪れる）。この**あいまいな時刻は、早い方の出現
   * （夏時間側・UTC換算で時刻が小さい方）のオフセットを採用する**と決める（ブラウザの `Date` 等が
   * 採る "compatible" 方式に合わせた規約。どちらを選ぶかは自明ではないため、ここに明記し検体で固定する）。
   *
   * @throws {RangeError} 形式違反（症状を隠さず失敗させる）
   * @returns オフセット付き ISO-8601 文字列。ユーザーTZ上で存在しない壁時計（DST開始の欠落時刻）の
   *   場合は `null`。
   * @example buildOffsetDateTimeFromLocalInput('2026-03-08T02:30') // ユーザーTZ=JST → "2026-03-08T02:30:00+09:00"
   * @example buildOffsetDateTimeFromLocalInput('2026-03-08T02:30') // ユーザーTZ=America/New_York → null（DSTで存在しない）
   */
  function buildOffsetDateTimeFromLocalInput(value: string): string | null {
    const match = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})(?::(\d{2}))?$/.exec(value)
    if (!match) throw new RangeError(`invalid datetime-local value: ${value}`)
    const [, y, mo, d, h, mi, sec = '00'] = match
    const offset = resolveUserTzOffsetMinutes(
      Number(y), Number(mo), Number(d), Number(h), Number(mi), Number(sec), userTimezone.value,
    )
    if (offset === null) return null
    const sign = offset >= 0 ? '+' : '-'
    const abs = Math.abs(offset)
    const hh = String(Math.floor(abs / 60)).padStart(2, '0')
    const mm = String(abs % 60).padStart(2, '0')
    return `${y}-${mo}-${d}T${h}:${mi}:${sec}${sign}${hh}:${mm}`
  }

  /**
   * 壁時計成分（timeZone のローカル時刻として解釈したい年月日時分秒）から UTCオフセット（分）を求める。
   *
   * DST 境界に対応するため、境界を跨ぐ可能性のある複数のオフセット候補を集め、それぞれ「そのオフセット
   * で UTC 瞬間を組み立て、timeZone で再フォーマットしたら元の壁時計に戻るか」を検証する:
   * - 検証を通る候補が無い ＝ 存在しない壁時計（春・スプリングフォワードの欠落）→ `null`
   * - 検証を通る候補が1つ ＝ 通常の一意な時刻 → それを返す
   * - 検証を通る候補が2つ ＝ 重複する時刻（秋・フォールバック）→ 早い方（UTC瞬間が小さい方＝夏時間側）
   */
  function resolveUserTzOffsetMinutes(
    y: number, mo: number, d: number, h: number, mi: number, sec: number, timeZone: string,
  ): number | null {
    const wallAsUtc = Date.UTC(y, mo - 1, d, h, mi, sec)
    // 素朴な壁時計=UTC近似から得た候補オフセットで UTC 瞬間を仮定し、その前後1日のオフセットも
    // 候補に加える（近傍の DST 境界の両側の regime を確実に拾うため）。
    const initialGuess = zoneOffsetMinutes(wallAsUtc, timeZone)
    const approxUtc = wallAsUtc - initialGuess * 60_000
    const dayMs = 24 * 3600_000
    const candidateOffsets = new Set([
      zoneOffsetMinutes(approxUtc - dayMs, timeZone),
      zoneOffsetMinutes(approxUtc, timeZone),
      zoneOffsetMinutes(approxUtc + dayMs, timeZone),
    ])

    const valid = [...candidateOffsets]
      .map(offset => ({ offset, utc: wallAsUtc - offset * 60_000 }))
      .filter(({ utc }) => reproducesWallClock(utc, timeZone, y, mo, d, h, mi, sec))

    if (valid.length === 0) return null
    valid.sort((a, b) => a.utc - b.utc)
    return valid[0]!.offset
  }

  /** 指定 UTC 瞬間における timeZone の UTC からのオフセット（分）。ブラウザTZに依存しない。 */
  function zoneOffsetMinutes(utcMillis: number, timeZone: string): number {
    const parts = wallClockPartsAt(utcMillis, timeZone)
    const asUtc = Date.UTC(parts.y, parts.mo - 1, parts.d, parts.h, parts.mi, parts.sec)
    return Math.round((asUtc - Math.floor(utcMillis / 1000) * 1000) / 60_000)
  }

  /** 指定 UTC 瞬間を timeZone で表示した壁時計が、指定した年月日時分秒と一致するか。 */
  function reproducesWallClock(
    utcMillis: number, timeZone: string, y: number, mo: number, d: number, h: number, mi: number, sec: number,
  ): boolean {
    const parts = wallClockPartsAt(utcMillis, timeZone)
    return parts.y === y && parts.mo === mo && parts.d === d
      && parts.h === h && parts.mi === mi && parts.sec === sec
  }

  /** 指定 UTC 瞬間を timeZone のローカル壁時計成分（年月日時分秒）に変換する。 */
  function wallClockPartsAt(utcMillis: number, timeZone: string) {
    const parts = new Intl.DateTimeFormat('en-US', {
      timeZone, hourCycle: 'h23', year: 'numeric', month: '2-digit', day: '2-digit',
      hour: '2-digit', minute: '2-digit', second: '2-digit',
    }).formatToParts(new Date(utcMillis))
    const get = (type: string) => Number(parts.find(p => p.type === type)?.value)
    return { y: get('year'), mo: get('month'), d: get('day'), h: get('hour'), mi: get('minute'), sec: get('second') }
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
    buildOffsetDateTimeFromLocalInput,
    buildDayStartStr,
    buildDayEndStr,
  }
}
