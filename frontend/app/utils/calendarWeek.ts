/**
 * F03.19 §6.5 週ビューの日付演算ユーティリティ（純関数）。
 *
 * `pages/calendar.vue`（週の前後移動・取得範囲の判定）と
 * `components/schedule/CalendarWeekGrid.vue`（7日分の列の組み立て）の双方が使う。
 *
 * **端末ローカルの日付とユーザー設定タイムゾーンの日付を混ぜないための置き場でもある。**
 * 「今日」を求める経路が片方だけ端末ローカルだと、両者の日付が土曜と日曜に分かれる時間帯に
 * 「今日」ボタンが隣の週を出す（Codex 検分 [3]）。日付の取得は必ず {@link todayInTimezone} を通す。
 */
import dayjs from 'dayjs'

/** 1日のミリ秒（= MINUTES_PER_DAY * MS_PER_MINUTE）。日付演算と占有判定の双方で使う。 */
const MS_PER_DAY = 86400000

const pad = (n: number) => String(n).padStart(2, '0')

/**
 * 'YYYY-MM-DD' を UTC 基準の通日番号へ。
 *
 * 日付そのものの加減算にのみ使う純粋な整数化であり、瞬間（インスタント）ではない。
 * タイムゾーン変換は {@link todayInTimezone} で済ませてから本関数へ渡すこと。
 */
export function dateToOrdinal(dateStr: string): number {
  const y = Number(dateStr.slice(0, 4))
  const m = Number(dateStr.slice(5, 7))
  const d = Number(dateStr.slice(8, 10))
  return Math.floor(Date.UTC(y, m - 1, d) / MS_PER_DAY)
}

/** 通日番号を 'YYYY-MM-DD' へ戻す。 */
export function ordinalToDate(ord: number): string {
  const dt = new Date(ord * MS_PER_DAY)
  return `${dt.getUTCFullYear()}-${pad(dt.getUTCMonth() + 1)}-${pad(dt.getUTCDate())}`
}

/**
 * その日付を含む週（**起点は日曜**・§6.5.3）の起点日を返す。
 *
 * 1970-01-01（通日番号 0）は木曜なので、日曜まで戻す量は `(ord + 4) % 7`。
 */
export function weekStartOf(dateStr: string): string {
  const ord = dateToOrdinal(dateStr)
  return ordinalToDate(ord - ((ord + 4) % 7))
}

/** `dateStr` から `days` 日ずらした日付。 */
export function shiftDate(dateStr: string, days: number): string {
  return ordinalToDate(dateToOrdinal(dateStr) + days)
}

/**
 * **ユーザー設定タイムゾーン**における「今日」の日付を返す。
 *
 * 端末ローカルの `new Date()` から日付を組み立ててはならない（Codex 検分 [3]）。
 * 例: 端末が America/Los_Angeles、ユーザー設定が Asia/Tokyo のとき、
 * 2026-08-01T23:00:00Z は端末では 8/1(土)、ユーザーにとっては 8/2(日) であり、
 * 属する週が丸ごと1つずれる。
 */
export function todayInTimezone(timezone: string): string {
  return dayjs().tz(timezone).format('YYYY-MM-DD')
}

/** 1日の分数。 */
export const MINUTES_PER_DAY = 1440

/** 占有判定に必要な予定の最小形。 */
export interface OccupancyInput {
  startAt: string
  endAt: string
  allDay?: boolean
}

/** ある日における予定の占有区間（その日の 0:00 からの分）。 */
export interface DayOccupancy {
  startMin: number
  endMin: number
}

/** 分 → ミリ秒。1日のミリ秒はファイル冒頭の MS_PER_DAY を共用する（MINUTES_PER_DAY * これと同値）。 */
const MS_PER_MINUTE = 60_000

/** ISO 文字列の時刻部（秒・ミリ秒は任意）。オフセット部は壁時計採用のため意図的に見ない。 */
const TIME_PART = /T(\d{2}):(\d{2})(?::(\d{2})(?:\.(\d{1,3}))?)?/

/**
 * ISO 文字列を「通日番号 * 1日 + 壁時計のミリ秒」へ変換する。
 *
 * カレンダー各所と同じく、BE から届く文字列の壁時計をそのまま採用する
 * （`startAt.slice(0, 10)` / `slice(11, 16)` と同じ流儀）。
 * 時刻部が無い日付のみの文字列は 0:00 とみなす（明示的な解釈であり、握りつぶしではない）。
 *
 * **分へ丸めずミリ秒まで保持するのが要点**（Codex 検分三巡目 [P2]）。
 * 以前は分で切り捨てていたため、`22:00:00` 〜 翌日 `00:00:30` の予定の終了が翌日 `00:00` と
 * 同値になり、「翌日を占有していない」と誤判定されて一覧からも時間グリッドからも消えていた。
 * API の日時は秒を含みうる。30秒しか掛かっていない日でも、掛かっている以上は存在を示す。
 */
export function absMillisOf(iso: string): number {
  const ord = dateToOrdinal(iso.slice(0, 10))
  const m = TIME_PART.exec(iso)
  if (!m) return ord * MS_PER_DAY
  const hours = Number(m[1])
  const minutes = Number(m[2])
  const seconds = m[3] === undefined ? 0 : Number(m[3])
  // '.5' のような桁落ち表記も 500ms として正しく読む（右詰めではなく左詰めの小数部）。
  const millis = m[4] === undefined ? 0 : Number(m[4].padEnd(3, '0'))
  return ord * MS_PER_DAY
    + hours * 3_600_000
    + minutes * MS_PER_MINUTE
    + seconds * 1_000
    + millis
}

/**
 * **「その日に予定が存在するか」の唯一の判定基準**（Codex 検分二巡目 [1]）。
 *
 * 占有していなければ `null`、していれば その日の 0:00 起点の占有区間を返す。
 *
 * 終了時刻は**排他的**に扱う。8/3 22:00〜8/4 00:00 の予定は 8/4 には一瞬も存在しないので
 * 8/4 では `null` を返す。以前は時間グリッドの分類（排他的）と一覧の抽出（日付文字列の
 * 包含比較）で基準が食い違っており、「その日の全予定」と称する一覧にその日には存在しない
 * 予定が混じっていた。**基準をこの関数一本に統一することが是正の本体である。**
 *
 * `allDay` の予定は日付単位で終日を占有する（BE は 23:59:59 を返すが、意味は「その日まる一日」）。
 */
export function eventDayOccupancy(event: OccupancyInput, ordinal: number): DayOccupancy | null {
  if (event.allDay) {
    const startOrd = dateToOrdinal(event.startAt.slice(0, 10))
    const endOrd = dateToOrdinal(event.endAt.slice(0, 10))
    if (ordinal < startOrd || ordinal > endOrd) return null
    return { startMin: 0, endMin: MINUTES_PER_DAY }
  }

  // 【占有の判定】は切り捨て前のミリ秒で行う（[P2]）。分へ丸めた値で比較すると、
  // 日付境界を秒単位でまたぐ予定が「掛かっていない」ことにされて消える。
  const absStart = absMillisOf(event.startAt)
  // 終了が開始より前の壊れたデータでも長さ負にはしない（消しもしない）。
  const absEnd = Math.max(absMillisOf(event.endAt), absStart)
  const dayStart = ordinal * MS_PER_DAY
  const dayEnd = dayStart + MS_PER_DAY

  const clipStart = Math.max(absStart, dayStart)
  const clipEnd = Math.min(absEnd, dayEnd)
  if (clipEnd > clipStart) return toOccupancy(clipStart - dayStart, clipEnd - dayStart)

  // 長さゼロの予定は「その瞬間が属する日」に置く。区間が空だからと消してはならない
  // （予定を無言で消す実装を作らないため）。
  if (absEnd === absStart && absStart >= dayStart && absStart < dayEnd) {
    const offset = absStart - dayStart
    return toOccupancy(offset, offset)
  }
  return null
}

/**
 * 【描画の位置決め】用に、その日の 0:00 起点のミリ秒を分へ落とす。
 *
 * 判定（ミリ秒・厳密）と描画（分・1時間=48px のレイアウト計算）を分ける境目がここ。
 * 秒は分の小数として残す — 丸めると 30秒の予定が startMin === endMin に潰れ、
 * 「占有していると判定したのに描けない」という新たな食い違いを生むため。
 * 極端に短い予定が視認できなくなる問題は、描画側の最低高さ（MIN_EVENT_H）が既に引き受けている。
 */
function toOccupancy(startOffsetMs: number, endOffsetMs: number): DayOccupancy {
  return { startMin: startOffsetMs / MS_PER_MINUTE, endMin: endOffsetMs / MS_PER_MINUTE }
}

/** その日に予定が存在するか（{@link eventDayOccupancy} と同一基準）。 */
export function eventOccupiesDate(event: OccupancyInput, dateStr: string): boolean {
  return eventDayOccupancy(event, dateToOrdinal(dateStr)) !== null
}
