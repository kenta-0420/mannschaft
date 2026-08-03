import { describe, it, expect } from 'vitest'
import { toLocalDateString } from '~/utils/localDate'

/**
 * localDate ユニットテスト（BE の LocalDate へ送る yyyy-MM-dd の組み立て）
 *
 * 背景（Issue #2508 ②「日付が 1 日ずれる」）:
 *   `Date.prototype.toISOString().slice(0, 10)` は UTC 基準の日付を返すため、
 *   UTC より東（日本など）では前日、UTC より西（America/*）では条件により翌日が送信される。
 *   `toLocalDateString` は「ユーザーがカレンダー上で指したその日」を返さなければならない。
 *
 * 検証観点:
 *   LDATE-001: JST（+09:00）のローカル 0:00 → その日付自身（toISOString だと前日になるケース）
 *   LDATE-002: 負のオフセット（America/Los_Angeles）でも選んだ日付が返る（toISOString だと翌日になるケース）
 *   LDATE-003: 月末・年末をまたぐ境界でもローカル日付が保たれる
 *   LDATE-004: 月日は 2 桁ゼロ埋めされる
 *   LDATE-005: Invalid Date は握りつぶさず例外にする
 */

/** 指定タイムゾーンで処理を実行する（Node は process.env.TZ の実行時変更を反映する）。 */
function withTz<T>(tz: string, fn: () => T): T {
  const original = process.env.TZ
  process.env.TZ = tz
  try {
    return fn()
  } finally {
    process.env.TZ = original
  }
}

describe('toLocalDateString', () => {
  it('LDATE-001: JST のローカル 0:00 はその日付自身を返す（toISOString だと前日）', () => {
    withTz('Asia/Tokyo', () => {
      const picked = new Date(2026, 6, 29) // 2026-07-29 00:00 JST
      // 前提: TZ 切り替えが効いており、素の toISOString では前日になること
      expect(picked.toISOString().slice(0, 10)).toBe('2026-07-28')
      expect(toLocalDateString(picked)).toBe('2026-07-29')
    })
  })

  it('LDATE-002: America/Los_Angeles の夜間でも選んだ日付を返す（toISOString だと翌日）', () => {
    withTz('America/Los_Angeles', () => {
      const evening = new Date(2026, 6, 29, 20, 0) // 2026-07-29 20:00 PDT = 2026-07-30T03:00Z
      expect(evening.toISOString().slice(0, 10)).toBe('2026-07-30')
      expect(toLocalDateString(evening)).toBe('2026-07-29')

      // 0:00 選択（DatePicker の既定形）も当日のまま
      const midnight = new Date(2026, 6, 29)
      expect(toLocalDateString(midnight)).toBe('2026-07-29')
    })
  })

  it('LDATE-003: 月末・年末の境界でもローカル日付が保たれる', () => {
    withTz('Asia/Tokyo', () => {
      const monthStart = new Date(2026, 7, 1) // 2026-08-01 00:00 JST
      expect(monthStart.toISOString().slice(0, 10)).toBe('2026-07-31')
      expect(toLocalDateString(monthStart)).toBe('2026-08-01')

      const yearStart = new Date(2027, 0, 1) // 2027-01-01 00:00 JST
      expect(yearStart.toISOString().slice(0, 10)).toBe('2026-12-31')
      expect(toLocalDateString(yearStart)).toBe('2027-01-01')

      // 閏日
      const leapDay = new Date(2028, 1, 29)
      expect(toLocalDateString(leapDay)).toBe('2028-02-29')
    })

    withTz('America/Los_Angeles', () => {
      const monthEndEvening = new Date(2026, 6, 31, 23, 30) // 2026-07-31 23:30 PDT = 2026-08-01T06:30Z
      expect(monthEndEvening.toISOString().slice(0, 10)).toBe('2026-08-01')
      expect(toLocalDateString(monthEndEvening)).toBe('2026-07-31')
    })
  })

  it('LDATE-004: 月日は 2 桁ゼロ埋めで返す', () => {
    withTz('Asia/Tokyo', () => {
      expect(toLocalDateString(new Date(2026, 0, 5))).toBe('2026-01-05')
      expect(toLocalDateString(new Date(2026, 11, 31))).toBe('2026-12-31')
    })
  })

  it('LDATE-005: Invalid Date は握りつぶさず例外を投げる', () => {
    expect(() => toLocalDateString(new Date('へんな日付'))).toThrow(RangeError)
  })
})
