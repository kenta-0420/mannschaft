import { afterEach, describe, expect, it, vi } from 'vitest'
import { dateToOrdinal, ordinalToDate, shiftDate, todayInTimezone, weekStartOf } from '~/utils/calendarWeek'

/**
 * F03.19 §6.5 週ビューの日付演算（Codex 検分 [3] の回帰テストを含む）。
 */
describe('utils/calendarWeek', () => {
  afterEach(() => {
    vi.useRealTimers()
  })

  it('weekStartOf: 週の起点は日曜（§6.5.3）', () => {
    // 2026-08-02 は日曜。日曜自身はその週の起点。
    expect(weekStartOf('2026-08-02')).toBe('2026-08-02')
    // 週の途中・週末はいずれも直前の日曜へ戻る
    expect(weekStartOf('2026-08-06')).toBe('2026-08-02')
    expect(weekStartOf('2026-08-08')).toBe('2026-08-02')
    // 土曜の翌日は次の週
    expect(weekStartOf('2026-08-09')).toBe('2026-08-09')
  })

  it('shiftDate: 月・年をまたいでも正しくずれる', () => {
    expect(shiftDate('2026-08-02', 7)).toBe('2026-08-09')
    expect(shiftDate('2026-08-02', -7)).toBe('2026-07-26')
    // 年跨ぎ
    expect(shiftDate('2026-12-27', 7)).toBe('2027-01-03')
    // 閏日
    expect(shiftDate('2028-02-28', 1)).toBe('2028-02-29')
  })

  it('dateToOrdinal / ordinalToDate は往復する', () => {
    for (const d of ['2026-01-01', '2026-08-06', '2028-02-29', '2030-12-31']) {
      expect(ordinalToDate(dateToOrdinal(d))).toBe(d)
    }
  })

  it('[3] todayInTimezone: 端末ローカルと日付境界で食い違ってもユーザー設定TZの日付を返す', () => {
    // 2026-08-01T23:00:00Z は
    //   America/Los_Angeles(UTC-7) では 8/1(土) 16:00
    //   Asia/Tokyo(UTC+9)          では 8/2(日) 08:00
    // 端末ローカルの日付を使っていると、この瞬間に「今日の週」が丸ごと1つずれる。
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-08-01T23:00:00Z'))

    expect(todayInTimezone('Asia/Tokyo')).toBe('2026-08-02')
    expect(todayInTimezone('America/Los_Angeles')).toBe('2026-08-01')

    // 週まで落とすと「隣の週」になることを明示する（本欠陥が利用者に見える形）
    expect(weekStartOf(todayInTimezone('Asia/Tokyo'))).toBe('2026-08-02')
    expect(weekStartOf(todayInTimezone('America/Los_Angeles'))).toBe('2026-07-26')
  })

  it('[3] todayInTimezone: 逆向き（日付が戻る側）でも正しい', () => {
    // 2026-08-02T03:00:00Z は Asia/Tokyo では 8/2(日) 12:00、UTC-8 では 8/1(土) 19:00。
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-08-02T03:00:00Z'))
    expect(todayInTimezone('Asia/Tokyo')).toBe('2026-08-02')
    expect(todayInTimezone('Pacific/Pitcairn')).toBe('2026-08-01')
  })
})
