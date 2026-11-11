/**
 * BE の {@code LocalDate}（`yyyy-MM-dd`）へ送る日付文字列を組み立てるユーティリティ。
 *
 * ## なぜ必要か（Issue #2508 ②「日付が 1 日ずれる」）
 *
 * `Date.prototype.toISOString()` は必ず **UTC 基準** で文字列化するため、
 * `toISOString().slice(0, 10)` は「ユーザーがカレンダー上で指した日」ではなく
 * 「その瞬間の UTC 上の日付」を返してしまう。
 *
 * ```
 * 2026-07-29 00:00 JST → toISOString() → "2026-07-28T15:00:00.000Z" → slice(0,10) → "2026-07-28"
 * ```
 *
 * UTC より東（日本を含む）では **前日**、UTC より西（`America/*`）では夕方以降に **翌日** が
 * 送信される。BE 側は `LocalDate` で受けるうえ、Jackson の `LocalDateDeserializer` は
 * `"...T...Z"` 形式を寛容に切り詰めて受理してしまうため、400 にもならず**静かに 1 日ずれた日付が保存される**。
 *
 * ## タイムゾーンの基準について（設計判断）
 *
 * 本関数は **ブラウザのローカル壁時計**（`Date#getFullYear()` / `getMonth()` / `getDate()`）を基準とし、
 * `users.timezone`（`useDatetime()` が表示に使うユーザー設定 TZ）への変換は **行わない**。理由:
 *
 * 1. `LocalDate` は「タイムゾーンを持たない暦上の日付」であり、変換すべき瞬間（instant）ではない。
 *    余計な TZ 変換を挟むと、変換のたびに日付がずれる余地を作るだけで、得るものがない。
 * 2. DatePicker はブラウザ TZ でカレンダーを描画し「今日」をハイライトする。ユーザーがクリックしたセルの
 *    年月日は `Date` のローカル壁時計成分そのものなので、これを取り出すのが唯一の無損失（WYSIWYG）な経路。
 *    ここで `users.timezone` へ変換すると、出張中などブラウザ TZ とプロフィール TZ が食い違うユーザーで
 *    「画面で選んだ日と違う日が送信される」という、いま直そうとしているのと同じ症状を再生産してしまう。
 * 3. 既存の {@link ~/utils/activityFields#toYmd} も同じ方針（ローカル壁時計）で実装済みで、実績がある。
 *
 * ## 時刻を伴う `OffsetDateTime` 系との関係（Issue #2508 Phase 2 で訂正）
 *
 * 以前ここには「`OffsetDateTime` 系は瞬間の変換が必要なので `users.timezone` 基準」と書かれていたが、
 * **これは誤りであり、この一文が実際にバグを容認していた**。`useDatetime().buildOffsetDateTimeStr()` は
 * ピッカーが返す `Date` を `dayjs(date).tz(users.timezone)` で投影し直しており、ブラウザ TZ と
 * プロフィール TZ が食い違うユーザーで上記 2. とまったく同じ「選んだ日が 1 日ずれる」症状を出していた。
 *
 * 正しい原則は日付・日時で共通である:
 *
 * > **ピッカー由来の `Date` は「瞬間」ではなく「ユーザーが画面で指した壁時計」である。**
 * > 年月日（および時分秒）はローカル壁時計成分として取り出し、`users.timezone` は
 * > **投影ではなく、その壁時計に付けるオフセットの決定にのみ**使う。
 *
 * したがって `LocalDate` は本関数、`OffsetDateTime` は
 * `useDatetime().buildOffsetDateTimeStr()`（内部で本関数を使い壁時計成分を取り出す）を使うこと。
 * 暦日が文字列で先に決まっている範囲検索は `buildDayStartStr` / `buildDayEndStr` を使う。
 * `yyyy-MM-dd` を作る処理を新規に書き起こしてはならない（同じバグを再生産する）。
 */

/**
 * `Date` をローカル壁時計基準の `yyyy-MM-dd` 文字列へ変換する。
 *
 * BE の `LocalDate` フィールド / `@RequestParam LocalDate` へ送る値はすべてこれを通すこと。
 * `toISOString().slice(0, 10)` は UTC 日付になるため使ってはならない。
 *
 * @param date 変換対象（DatePicker が返すローカル 0:00 の `Date` を想定）
 * @returns `yyyy-MM-dd` 形式のローカル日付
 * @throws {RangeError} `date` が Invalid Date の場合（症状を隠さず失敗させる）
 *
 * @example toLocalDateString(new Date(2026, 6, 29)) // JST → "2026-07-29"
 */
export function toLocalDateString(date: Date): string {
  if (Number.isNaN(date.getTime())) {
    throw new RangeError('toLocalDateString: Invalid Date は yyyy-MM-dd に変換できません')
  }
  const y = String(date.getFullYear()).padStart(4, '0')
  const m = String(date.getMonth() + 1).padStart(2, '0')
  const d = String(date.getDate()).padStart(2, '0')
  return `${y}-${m}-${d}`
}

/**
 * `yyyy-MM-dd`（BE の `LocalDate`）を、いかなるTZの影響も受けずにロケール表示用の文字列へ変換する。
 *
 * `new Date("2026-08-20")` は **UTC の午前0時** として解釈されるため、これをブラウザTZで
 * 描画すると UTC より西のTZ（`America/Los_Angeles` 等）では前日「8/19」にずれる。
 * `LocalDate` は暦日そのものであり瞬間ではないため、本関数のように TZ 変換を経由しない
 * 経路（`new Date(y, m - 1, d)` はローカルTZの壁時計成分から構築するため UTC を経由しない）
 * で扱うこと（本ファイル冒頭の設計原則を参照）。
 *
 * @param ymd `yyyy-MM-dd` 形式の暦日（BE の `LocalDate`）
 * @param locale `Intl.DateTimeFormat` へ渡すロケール（例: 'ja-JP'）
 * @example formatLocalDateOnly('2026-08-20', 'ja-JP') // → "2026/8/20"（TZに関わらず 8/20）
 */
export function formatLocalDateOnly(ymd: string, locale: string): string {
  const [y, m, d] = ymd.split('-').map(Number)
  return new Intl.DateTimeFormat(locale, { dateStyle: 'medium' }).format(new Date(y!, m! - 1, d!))
}

/**
 * `Date` をローカル壁時計基準の `yyyy-MM-ddTHH:mm` 文字列へ変換する（分単位・秒は切り捨て）。
 *
 * {@link toLocalDateString} と同じ設計原則（ピッカー由来の `Date` はローカル壁時計成分として
 * 取り出す。UTC 経由の `toISOString()` は使わない）を時刻付きフィールド（活動テンプレートの
 * `DATETIME` カスタムフィールド等）向けに適用したもの。
 *
 * @param date 変換対象（DatePicker（show-time）が返す `Date` を想定）
 * @throws {RangeError} `date` が Invalid Date の場合（症状を隠さず失敗させる）
 * @example toLocalDateTimeString(new Date(2026, 6, 29, 9, 5)) // → "2026-07-29T09:05"
 */
export function toLocalDateTimeString(date: Date): string {
  if (Number.isNaN(date.getTime())) {
    throw new RangeError('toLocalDateTimeString: Invalid Date は yyyy-MM-ddTHH:mm に変換できません')
  }
  const h = String(date.getHours()).padStart(2, '0')
  const min = String(date.getMinutes()).padStart(2, '0')
  return `${toLocalDateString(date)}T${h}:${min}`
}

// ---------------------------------------------------------------------------
// ロケール対応の日時整形（純関数）。
//
// 日時整形の正準はここと `useDatetime()`（内部で本節の関数を呼ぶ）。画面側で曜日配列・
// 「m月d日」・「〜」などを自前で組み立ててはならない（ロケールごとの書式が二重管理になる）。
// ---------------------------------------------------------------------------

const INTL_LOCALES: Record<string, string> = {
  ja: 'ja-JP',
  en: 'en-US',
  zh: 'zh-CN',
  ko: 'ko-KR',
  es: 'es-ES',
  de: 'de-DE',
}

/** アプリのロケールコード（ja/en/zh/ko/es/de）を Intl 用の BCP 47 タグへ変換する。未知の値は ja-JP。 */
export function toIntlLocale(locale: string): string {
  return INTL_LOCALES[locale.split('-')[0]!.toLowerCase()] ?? 'ja-JP'
}

const DATE_OPTS: Intl.DateTimeFormatOptions = { year: 'numeric', month: '2-digit', day: '2-digit' }
const TIME_OPTS: Intl.DateTimeFormatOptions = { hour: '2-digit', minute: '2-digit', hourCycle: 'h23' }
const TIME_SEC_OPTS: Intl.DateTimeFormatOptions = { ...TIME_OPTS, second: '2-digit' }

function isValidInstant(date: unknown): date is Date {
  return date instanceof Date && !Number.isNaN(date.getTime())
}

/**
 * 瞬間（Date）を timeZone の数値日付へ整形する。ja は `YYYY/MM/DD`（従来と同一）。不正な Date は ''。
 * @example formatInstantDate(d, 'ja', 'Asia/Tokyo') // "2026/05/22"
 */
export function formatInstantDate(date: Date, locale: string, timeZone: string): string {
  if (!isValidInstant(date)) return ''
  return new Intl.DateTimeFormat(toIntlLocale(locale), { ...DATE_OPTS, timeZone }).format(date)
}

/** 瞬間を timeZone の `HH:mm`（24時間・秒なし）へ整形する。不正な Date は ''。 */
export function formatInstantTime(date: Date, locale: string, timeZone: string): string {
  if (!isValidInstant(date)) return ''
  return new Intl.DateTimeFormat(toIntlLocale(locale), { ...TIME_OPTS, timeZone }).format(date)
}

/** 瞬間を timeZone の日付＋時刻へ整形する。ja は `YYYY/MM/DD HH:mm`（従来と同一）。不正な Date は ''。 */
export function formatInstantDateTime(date: Date, locale: string, timeZone: string): string {
  if (!isValidInstant(date)) return ''
  return `${formatInstantDate(date, locale, timeZone)} ${formatInstantTime(date, locale, timeZone)}`
}

/**
 * 瞬間を timeZone の日付＋秒付き時刻（`YYYY/MM/DD HH:mm:ss`）へ整形する。
 * 分単位では区別できない（同じ分に作成された）レコードの識別用。不正な Date は ''。
 */
export function formatInstantDateTimeSeconds(date: Date, locale: string, timeZone: string): string {
  if (!isValidInstant(date)) return ''
  const time = new Intl.DateTimeFormat(toIntlLocale(locale), { ...TIME_SEC_OPTS, timeZone }).format(date)
  return `${formatInstantDate(date, locale, timeZone)} ${time}`
}

/**
 * ISO-8601 風の文字列を検証して Date にする。不正・空は null。
 * `yyyy-MM-dd` で始まる文字列は全体を完全一致で検証し（暦日は実在、時刻は 0-23/0-59/0-59、
 * 小数秒・Z・±hh:mm / ±hhmm のオフセットは可）、dayjs の自動補正（2月30日→3月2日 等）を許さない。
 * それ以外の形式は従来どおり Date の解釈に委ねる。
 */
export function parseInstantStrict(iso: string | null | undefined): Date | null {
  if (!iso) return null
  if (/^\d{4}-\d{2}-\d{2}/.test(iso)) {
    const m = /^(\d{4}-\d{2}-\d{2})(?:[T ](\d{2}:\d{2}(?::\d{2}(?:\.\d+)?)?)(Z|[+-]\d{2}(?::?\d{2})?)?)?$/.exec(iso)
    if (!m) return null
    if (!ymdToUtcDate(m[1])) return null
    if (m[2] && !hhmmToUtcDate(m[2].replace(/\.\d+$/, ''))) return null
    if (m[3] && m[3] !== 'Z') {
      const off = /^[+-](\d{2})(?::?(\d{2}))?$/.exec(m[3])!
      if (Number(off[1]) > 23 || Number(off[2] ?? 0) > 59) return null
    }
  }
  const d = new Date(iso)
  return Number.isNaN(d.getTime()) ? null : d
}

/** `yyyy-MM-dd` を UTC 暦日の Date にする（TZ 変換を経由しない。表示は必ず timeZone:'UTC' で行う）。空・不正は null。 */
function ymdToUtcDate(ymd: string | null | undefined): Date | null {
  if (!ymd) return null
  // 入力全体の完全一致 + 往復確認（Date.UTC は 2月30日 を 3月2日 へ自動補正するため、補正された値は不正として弾く）
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(ymd)
  if (!m) return null
  const y = Number(m[1])
  const mo = Number(m[2])
  const day = Number(m[3])
  const d = new Date(Date.UTC(y, mo - 1, day))
  if (Number.isNaN(d.getTime())) return null
  return d.getUTCFullYear() === y && d.getUTCMonth() === mo - 1 && d.getUTCDate() === day ? d : null
}

const CALENDAR_DAY_OPTS: Intl.DateTimeFormatOptions = {
  month: 'short', day: 'numeric', weekday: 'short', timeZone: 'UTC',
}

function isJa(locale: string): boolean {
  return locale.split('-')[0]!.toLowerCase() === 'ja'
}

/** ja の暦日表記（従来の緊急休業と同一: 「11月4日（水）」）。 */
function formatCalendarDayJa(date: Date): string {
  const weekday = new Intl.DateTimeFormat('ja-JP', { weekday: 'short', timeZone: 'UTC' }).format(date)
  return `${date.getUTCMonth() + 1}月${date.getUTCDate()}日（${weekday}）`
}

/**
 * 暦日（`yyyy-MM-dd`）を曜日付きで整形する。UTC 暦日として扱うため西側 TZ でも前日にならない。
 * 空・不正入力は '' を返す（useDatetime の safeParse と同じ流儀）。
 * ja は従来表記「11月4日（水）」、他言語は Intl。
 */
export function formatCalendarDay(ymd: string | null | undefined, locale: string): string {
  const d = ymdToUtcDate(ymd)
  if (!d) return ''
  return isJa(locale) ? formatCalendarDayJa(d) : new Intl.DateTimeFormat(toIntlLocale(locale), CALENDAR_DAY_OPTS).format(d)
}

/**
 * 暦日の範囲を曜日付きで整形する（同日なら単日表示）。どちらかが空・不正なら '' 。
 * ja は従来表記「11月4日（水）〜11月5日（木）」、他言語は Intl formatRange。
 */
export function formatCalendarDayRange(
  startYmd: string | null | undefined, endYmd: string | null | undefined, locale: string,
): string {
  const s = ymdToUtcDate(startYmd)
  const e = ymdToUtcDate(endYmd)
  if (!s || !e) return ''
  if (s.getTime() === e.getTime()) return formatCalendarDay(startYmd, locale)
  if (isJa(locale)) return `${formatCalendarDayJa(s)}〜${formatCalendarDayJa(e)}`
  const fmt = new Intl.DateTimeFormat(toIntlLocale(locale), CALENDAR_DAY_OPTS)
  return typeof fmt.formatRange === 'function' ? fmt.formatRange(s, e) : `${fmt.format(s)} – ${fmt.format(e)}`
}

/** `HH:mm` / `H:mm` / `HH:mm:ss` を UTC 基準の Date（1970-01-01）にする。不正は null。 */
function hhmmToUtcDate(hhmm: string | null | undefined): Date | null {
  if (!hhmm) return null
  // 入力全体の完全一致 + 範囲検証（時 0-23 / 分 0-59 / 秒 0-59）。範囲外を Date.UTC に自動補正させない
  const m = /^(\d{1,2}):(\d{2})(?::(\d{2}))?$/.exec(hhmm)
  if (!m) return null
  const h = Number(m[1])
  const mi = Number(m[2])
  const sec = m[3] === undefined ? 0 : Number(m[3])
  if (h > 23 || mi > 59 || sec > 59) return null
  return new Date(Date.UTC(1970, 0, 1, h, mi))
}

/** 時刻（`HH:mm[:ss]`、秒は表示しない）を 24 時間表記で整形する。ja は従来どおり `09:00`。空・不正は ''。 */
export function formatClockTime(hhmm: string | null | undefined, locale: string): string {
  const d = hhmmToUtcDate(hhmm)
  if (!d) return ''
  return new Intl.DateTimeFormat(toIntlLocale(locale), { ...TIME_OPTS, timeZone: 'UTC' }).format(d)
}

/** 時刻範囲の区切り。ja は従来どおり「〜」、それ以外は en ダッシュ（書式は各端点を単独整形した結果をそのまま繋ぐ） */
const TIME_RANGE_SEP: Record<string, string> = { ja: '〜' }

/**
 * 時刻の範囲（`HH:mm` / `HH:mm:ss`、秒は表示しない）をロケール書式（24時間表記）で整形する。
 * Intl formatRange は ja で「9時00分～」の文字表記に切り替わり単独整形と不揃いになるため、端点を個別に整形して繋ぐ。
 * @example formatTimeRange('09:00:00', '11:00:00', 'ja') // "09:00〜11:00"
 */
export function formatTimeRange(
  startHhmm: string | null | undefined, endHhmm: string | null | undefined, locale: string,
): string {
  const s0 = formatClockTime(startHhmm, locale)
  const e0 = formatClockTime(endHhmm, locale)
  if (!s0 || !e0) return ''
  const sep = TIME_RANGE_SEP[locale.split('-')[0]!.toLowerCase()] ?? ' – '
  return `${s0}${sep}${e0}`
}
