import { describe, it, expect } from 'vitest'
import { toLocalDateString, formatLocalDateOnly } from '~/utils/localDate'

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

/**
 * formatLocalDateOnly ユニットテスト（payments/subscribe/[itemId].vue の termStartsOn/termEndsOn 用）。
 *
 * 背景（殿の指摘・Issue #2508 Phase 3 追補）:
 *   `new Date("2026-08-20")` は BE の `LocalDate` 文字列を UTC の午前0時として解釈するため、
 *   これをそのままロケール表示すると UTC より西のTZ（America/Los_Angeles 等）では
 *   前日「8/19」にずれる。`LocalDate` は暦日そのものであり瞬間ではないため TZ 変換しては
 *   ならない。`formatLocalDateOnly` はこの誤りを踏まずに壁時計として扱う。
 *
 * 検証観点:
 *   LDONLY-001: ブラウザTZ=America/Los_Angeles で "2026-08-20" は 8/20 と表示される（8/19 にならない）
 *   LDONLY-002: 壊れていた実装（new Date(ymd) をそのまま Intl.DateTimeFormat に渡す）は
 *               同条件で 8/19 になることを示し、比較対象として明示する
 */
describe('formatLocalDateOnly', () => {
  it('LDONLY-001: America/Los_Angeles でも "2026-08-20" は 8/20 と表示される', () => {
    withTz('America/Los_Angeles', () => {
      const formatted = formatLocalDateOnly('2026-08-20', 'ja-JP')
      expect(formatted).toContain('8/20')
      // SLR-002 と同様、壊れていた側の値（前日）とは一致しないことも明示する
      expect(formatted).not.toContain('8/19')
    })
  })

  it('LDONLY-002: 壊れていた実装（new Date(ymd) を直接 Intl に渡す）は同条件で前日になる（比較対象）', () => {
    withTz('America/Los_Angeles', () => {
      const broken = new Intl.DateTimeFormat('ja-JP', { dateStyle: 'medium' }).format(new Date('2026-08-20'))
      expect(broken).toContain('8/19')
    })
  })

  it('LDONLY-003: Asia/Tokyo（UTCより東）でも 8/20 のまま', () => {
    withTz('Asia/Tokyo', () => {
      expect(formatLocalDateOnly('2026-08-20', 'ja-JP')).toContain('8/20')
    })
  })
})
