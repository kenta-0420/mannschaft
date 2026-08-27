import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  dateToOrdinal,
  eventDayOccupancy,
  eventOccupiesDate,
  ordinalToDate,
  shiftDate,
  todayInTimezone,
  weekStartOf,
} from '~/utils/calendarWeek'

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

  describe('[1] eventOccupiesDate / eventDayOccupancy — その日に存在するかの唯一の基準', () => {
    const boundary = {
      startAt: '2026-08-03T22:00:00+09:00',
      endAt: '2026-08-04T00:00:00+09:00',
      allDay: false,
    }

    it('零時ちょうどに終わる予定は、終わった日には存在しない（終了は排他的）', () => {
      expect(eventOccupiesDate(boundary, '2026-08-03')).toBe(true)
      expect(eventOccupiesDate(boundary, '2026-08-04')).toBe(false)
      // 8/3 側の占有は 22:00〜24:00
      expect(eventDayOccupancy(boundary, dateToOrdinal('2026-08-03')))
        .toEqual({ startMin: 22 * 60, endMin: 24 * 60 })
      expect(eventDayOccupancy(boundary, dateToOrdinal('2026-08-04'))).toBeNull()
    })

    it('零時を1分でも過ぎれば翌日にも存在する', () => {
      const spills = { ...boundary, endAt: '2026-08-04T00:01:00+09:00' }
      expect(eventOccupiesDate(spills, '2026-08-04')).toBe(true)
      expect(eventDayOccupancy(spills, dateToOrdinal('2026-08-04')))
        .toEqual({ startMin: 0, endMin: 1 })
    })

    it('中間日は24時間フル占有として返る（週ビューが終日帯へ送る判定に使う）', () => {
      const long = {
        startAt: '2026-08-05T22:00:00+09:00',
        endAt: '2026-08-07T02:00:00+09:00',
        allDay: false,
      }
      expect(eventDayOccupancy(long, dateToOrdinal('2026-08-06')))
        .toEqual({ startMin: 0, endMin: 1440 })
    })

    it('allDay は日付単位で終日を占有し、範囲外の日には存在しない', () => {
      const allDay = {
        startAt: '2026-08-03T00:00:00+09:00',
        endAt: '2026-08-05T23:59:59+09:00',
        allDay: true,
      }
      expect(eventOccupiesDate(allDay, '2026-08-02')).toBe(false)
      for (const d of ['2026-08-03', '2026-08-04', '2026-08-05']) {
        expect(eventDayOccupancy(allDay, dateToOrdinal(d))).toEqual({ startMin: 0, endMin: 1440 })
      }
      expect(eventOccupiesDate(allDay, '2026-08-06')).toBe(false)
    })

    it('長さゼロの予定は消さず、その瞬間が属する日に置く', () => {
      const instant = {
        startAt: '2026-08-04T09:00:00+09:00',
        endAt: '2026-08-04T09:00:00+09:00',
        allDay: false,
      }
      expect(eventOccupiesDate(instant, '2026-08-04')).toBe(true)
      expect(eventDayOccupancy(instant, dateToOrdinal('2026-08-04')))
        .toEqual({ startMin: 540, endMin: 540 })
      expect(eventOccupiesDate(instant, '2026-08-03')).toBe(false)
    })

    it('終了が開始より前の壊れたデータでも、その予定を消さない', () => {
      const broken = {
        startAt: '2026-08-04T10:00:00+09:00',
        endAt: '2026-08-04T09:00:00+09:00',
        allDay: false,
      }
      expect(eventOccupiesDate(broken, '2026-08-04')).toBe(true)
    })
  })
})
