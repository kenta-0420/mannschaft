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
