import { afterAll, describe, expect, it } from 'vitest'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'

/**
 * ユーザーTZ自身の DST 境界（2026-09-24 Codex 再検分 P2）。
 *
 * ブラウザTZ=Asia/Tokyo（DSTなし）・ユーザーTZ=America/New_York（DSTあり）の組み合わせでも、
 * ブラウザTZに依存せずユーザーTZ側の DST 境界が正しく検出されることを確かめる
 * （本関数は文字列成分と `Intl.DateTimeFormat` の明示 timeZone 指定のみを使い、ブラウザローカルの
 * `Date` 成分を経由しないため、ブラウザTZを変えても結果は変わらないはず）。
 */

const originalTz = process.env.TZ
process.env.TZ = 'Asia/Tokyo'

mockNuxtImport('useAuthStore', () => () => ({ user: { timezone: 'America/New_York' }, loadFromStorage: () => undefined }))

afterAll(() => {
  process.env.TZ = originalTz
})

describe('useDatetime().buildOffsetDateTimeFromLocalInput — ブラウザTokyo・ユーザーNY', () => {
  it('春・DST開始で存在しない壁時計（2026-03-08T02:30）は null を返す', () => {
    const { buildOffsetDateTimeFromLocalInput } = useDatetime()
    expect(buildOffsetDateTimeFromLocalInput('2026-03-08T02:30')).toBeNull()
  })

  it('秋・DST終了で重複する壁時計（2026-11-01T01:30）は早い方（夏時間側 -04:00）を返す', () => {
    const { buildOffsetDateTimeFromLocalInput } = useDatetime()
    expect(buildOffsetDateTimeFromLocalInput('2026-11-01T01:30')).toBe('2026-11-01T01:30:00-04:00')
  })

  it('通常の時刻は壁時計のままユーザーTZのオフセットを付ける', () => {
    const { buildOffsetDateTimeFromLocalInput } = useDatetime()
    expect(buildOffsetDateTimeFromLocalInput('2026-06-15T12:00')).toBe('2026-06-15T12:00:00-04:00')
  })
})
