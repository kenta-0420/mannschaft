import { afterAll, describe, expect, it } from 'vitest'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'

/**
 * ユーザーTZ自身の DST 境界（2026-09-24 Codex 再検分 P2）。
 *
 * ブラウザTZ=America/New_York・ユーザーTZ=America/New_York（同一）で、ユーザーTZ側の
 * DST 開始で存在しない壁時計（春・スプリングフォワード）を渡したら黙って変換せず `null` を返すこと、
 * DST 終了で重複する壁時計（秋・フォールバック）は早い方（夏時間側）のオフセットを一意に選ぶこと、
 * 通常の時刻は従来どおり変換できることを確かめる。
 */

const originalTz = process.env.TZ
process.env.TZ = 'America/New_York'

mockNuxtImport('useAuthStore', () => () => ({ user: { timezone: 'America/New_York' }, loadFromStorage: () => undefined }))

afterAll(() => {
  process.env.TZ = originalTz
})

describe('useDatetime().buildOffsetDateTimeFromLocalInput — ブラウザNY・ユーザーNY', () => {
  it('春・DST開始で存在しない壁時計（2026-03-08T02:30）は null を返す', () => {
    const { buildOffsetDateTimeFromLocalInput } = useDatetime()
    expect(buildOffsetDateTimeFromLocalInput('2026-03-08T02:30')).toBeNull()
  })

  it('秋・DST終了で重複する壁時計（2026-11-01T01:30）は早い方（夏時間側 -04:00）を返す', () => {
    const { buildOffsetDateTimeFromLocalInput } = useDatetime()
    expect(buildOffsetDateTimeFromLocalInput('2026-11-01T01:30')).toBe('2026-11-01T01:30:00-04:00')
  })

  it('通常の時刻（夏時間中）は壁時計のままユーザーTZのオフセットを付ける', () => {
    const { buildOffsetDateTimeFromLocalInput } = useDatetime()
    expect(buildOffsetDateTimeFromLocalInput('2026-06-15T12:00')).toBe('2026-06-15T12:00:00-04:00')
  })

  it('通常の時刻（標準時中）は壁時計のままユーザーTZのオフセットを付ける', () => {
    const { buildOffsetDateTimeFromLocalInput } = useDatetime()
    expect(buildOffsetDateTimeFromLocalInput('2026-12-15T12:00')).toBe('2026-12-15T12:00:00-05:00')
  })
})
