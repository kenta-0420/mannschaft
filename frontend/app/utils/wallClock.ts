import dayjs from 'dayjs'
import type { Dayjs } from 'dayjs'

/**
 * 壁時計文字列（`YYYY-MM-DDTHH:mm:ss` 等、オフセット無し）をタイムゾーン `tz` の瞬間へ変換する共通部品。
 *
 * `dayjs.tz(文字列, tz)` を直接呼んではならない。秋の重複（夏時間終了で同じ壁時計が二度来る時間帯）の
 * 解決に「**現在時刻**のオフセット」を第一推測として使う（dayjs の timezone プラグイン
 * `previousOffset = tzOffset(+d(), timezone)`）ため、同じ入力でも実行した日時で結果が変わる。
 * 例: America/New_York の 2026-11-01 01:30 は、切替（06:00Z）の前に実行すると -04:00、後だと -05:00。
 * 画面（週グリッド）と保存（フォーム）で結果が食い違い、範囲が 90分にも 30分にもなる（CMP-261101-1404）。
 *
 * 本関数は重複時刻を**常に先に来る側（夏時間側）**へ固定する。
 * `useDatetime.buildOffsetDateTimeFromLocalInput` の規約（早い方の出現を採る）と同じである。
 * 一意な時刻、および春のギャップ（存在しない壁時計）は dayjs の結果をそのまま返す。
 */
export function parseWallClockInTz(wallClock: string, tz: string): Dayjs {
  const resolved = dayjs.tz(wallClock, tz)
  if (!resolved.isValid()) return resolved
  const offsetBefore = dayjs(resolved.valueOf() - 24 * 3_600_000).tz(tz).utcOffset()
  const shiftMin = resolved.utcOffset() - offsetBefore
  // 切替でオフセットが小さくなる側（= 重複を作る側）だけが対象。
  if (shiftMin >= 0) return resolved
  const earlier = dayjs(resolved.valueOf() + shiftMin * 60_000).tz(tz)
  // 入力の壁時計をそのまま再現できる場合のみ採用（再現できなければ重複ではない）。
  const wall = dayjs.utc(wallClock).format('YYYY-MM-DDTHH:mm:ss')
  return earlier.format('YYYY-MM-DDTHH:mm:ss') === wall ? earlier : resolved
}
