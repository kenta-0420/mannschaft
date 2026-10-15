import { afterAll, describe, expect, it } from 'vitest'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'

/**
 * `<input type="datetime-local">` の値（`YYYY-MM-DDTHH:mm`）をユーザーTZのオフセット付き ISO-8601 にする
 * 変換の検体（2026-09-24 Codex 再検分 P2）。
 *
 * `new Date(value)` を経由すると、ブラウザTZで存在しない時刻（DST 開始時刻）が1時間ずれて正規化され、
 * ずれた壁時計にユーザーTZのオフセットを付けて送ってしまう。文字列の成分から直接ユーザーTZで解釈する
 * ことを、ブラウザTZ=America/New_York（2026-03-08 02:00 に DST 開始）・ユーザーTZ=Asia/Tokyo で確かめる。
 */

const originalTz = process.env.TZ
process.env.TZ = 'America/New_York'

mockNuxtImport('useAuthStore', () => () => ({ user: { timezone: 'Asia/Tokyo' }, loadFromStorage: () => undefined }))

afterAll(() => {
  process.env.TZ = originalTz
})

describe('useDatetime().buildOffsetDateTimeFromLocalInput', () => {
  it('ブラウザTZで存在しない DST 開始時刻でも、入力した壁時計のままユーザーTZのオフセットを付ける', () => {
    const { buildOffsetDateTimeFromLocalInput } = useDatetime()
    expect(buildOffsetDateTimeFromLocalInput('2026-03-08T02:30')).toBe('2026-03-08T02:30:00+09:00')
  })

  it('通常の時刻も壁時計のままユーザーTZのオフセットを付ける', () => {
    const { buildOffsetDateTimeFromLocalInput } = useDatetime()
    expect(buildOffsetDateTimeFromLocalInput('2026-10-01T00:00')).toBe('2026-10-01T00:00:00+09:00')
  })

  it('秒付き（YYYY-MM-DDTHH:mm:ss）の入力も受け付ける', () => {
    const { buildOffsetDateTimeFromLocalInput } = useDatetime()
    expect(buildOffsetDateTimeFromLocalInput('2026-10-01T12:34:56')).toBe('2026-10-01T12:34:56+09:00')
  })

  it('形式違反は症状を隠さず RangeError', () => {
    const { buildOffsetDateTimeFromLocalInput } = useDatetime()
    expect(() => buildOffsetDateTimeFromLocalInput('2026/10/01 00:00')).toThrow(RangeError)
    expect(() => buildOffsetDateTimeFromLocalInput('')).toThrow(RangeError)
  })
})
