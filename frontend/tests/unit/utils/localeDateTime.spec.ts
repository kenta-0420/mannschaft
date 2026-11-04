import { describe, it, expect } from 'vitest'
import {
  formatCalendarDay,
  formatCalendarDayRange,
  formatClockTime,
  formatInstantDate,
  formatInstantDateTime,
  formatInstantTime,
  formatTimeRange,
} from '~/utils/localDate'

/**
 * ロケール対応の日時整形（純関数）ユニットテスト（CMP-261104-0335）。
 * AC 0335-1（6言語で日本語が残らない）/ 0335-2（西側TZで前日にずれない）/
 * 0335-3（時刻範囲に秒が出ない）/ 0335-5（同日・月跨ぎ・年跨ぎ・閏日）/ ja 出力が従来と同一。
 */
const LOCALES = ['ja', 'en', 'zh', 'ko', 'es', 'de'] as const
const JA_ONLY = /[水〜]/

describe('瞬間の整形: ja は従来出力と同一', () => {
  const d = new Date('2026-05-22T09:15:20+09:00')
  it('formatInstantDate', () => expect(formatInstantDate(d, 'ja', 'Asia/Tokyo')).toBe('2026/05/22'))
  it('formatInstantDateTime', () => expect(formatInstantDateTime(d, 'ja', 'Asia/Tokyo')).toBe('2026/05/22 09:15'))
  it('formatInstantTime', () => expect(formatInstantTime(d, 'ja', 'Asia/Tokyo')).toBe('09:15'))
  it('ユーザーTZを反映する', () => {
    expect(formatInstantDateTime(d, 'ja', 'America/Los_Angeles')).toBe('2026/05/21 17:15')
  })
  it('en は月日年の順', () => {
    expect(formatInstantDate(d, 'en', 'Asia/Tokyo')).toBe('05/22/2026')
  })
  it('de は DD.MM.YYYY', () => {
    expect(formatInstantDate(d, 'de', 'Asia/Tokyo')).toBe('22.05.2026')
  })
})

describe('暦日の曜日付き整形', () => {
  it('ja は従来の緊急休業表記と厳密一致（全角括弧）', () => {
    expect(formatCalendarDay('2026-11-04', 'ja')).toBe('11月4日（水）')
    expect(formatCalendarDay('2026-12-01', 'ja')).toBe('12月1日（火）')
  })
  it.each(['en', 'es', 'de'] as const)('%s は日本語が残らない', (loc) => {
    expect(formatCalendarDay('2026-11-04', loc)).not.toMatch(JA_ONLY)
  })
  it('en は Wed を含む', () => expect(formatCalendarDay('2026-11-04', 'en')).toContain('Wed'))
  it('de は Mi を含む', () => expect(formatCalendarDay('2026-11-04', 'de')).toMatch(/Mi/))
  it('閏日 2028-02-29 が存在する', () => expect(formatCalendarDay('2028-02-29', 'en')).toContain('29'))
})

describe('0335-2 西側TZで前日にずれない', () => {
  it.each(['America/Los_Angeles', 'Pacific/Honolulu', 'Asia/Tokyo'])('TZ=%s でも 11/4 水', (tz) => {
    const orig = process.env.TZ
    process.env.TZ = tz
    try {
      const out = formatCalendarDay('2026-11-04', 'en')
      expect(out).toContain('4')
      expect(out).toContain('Wed')
    }
    finally {
      process.env.TZ = orig
    }
  })
})

describe('暦日範囲', () => {
  it('同日は単日と同じ', () => {
    expect(formatCalendarDayRange('2026-11-04', '2026-11-04', 'en')).toBe(formatCalendarDay('2026-11-04', 'en'))
  })
  it('月跨ぎ', () => {
    const out = formatCalendarDayRange('2026-10-30', '2026-11-02', 'en')
    expect(out).toContain('Oct')
    expect(out).toContain('Nov')
  })
  it('年跨ぎ', () => {
    const out = formatCalendarDayRange('2026-12-31', '2027-01-02', 'de')
    expect(out).toContain('31')
    expect(out).toContain('2')
    expect(out).not.toMatch(JA_ONLY)
  })
  it('閏日を跨ぐ', () => {
    const out = formatCalendarDayRange('2028-02-28', '2028-03-01', 'en')
    expect(out).toContain('Feb')
    expect(out).toContain('Mar')
  })
  it.each(['en', 'es', 'de'] as const)('%s の範囲に「〜」が出ない', (loc) => {
    expect(formatCalendarDayRange('2026-11-01', '2026-11-03', loc)).not.toContain('〜')
  })
})

describe('0335-3 時刻範囲', () => {
  it.each(LOCALES)('%s: 秒が出ない', (loc) => {
    const out = formatTimeRange('09:00:00', '11:00:00', loc)
    expect(out).not.toMatch(/\d{2}:\d{2}:\d{2}/)
    expect(out).toMatch(/09:00/)
    expect(out).toMatch(/11:00/)
  })
  it.each(['en', 'es', 'de'] as const)('%s: 「〜」が出ない', (loc) => {
    expect(formatTimeRange('09:00', '11:00', loc)).not.toContain('〜')
  })
})

describe('ja は従来の緊急休業の出力と厳密一致', () => {
  it('単日', () => expect(formatCalendarDayRange('2026-11-04', '2026-11-04', 'ja')).toBe('11月4日（水）'))
  it('同月期間', () => expect(formatCalendarDayRange('2026-11-04', '2026-11-06', 'ja')).toBe('11月4日（水）〜11月6日（金）'))
  it('月跨ぎ', () => expect(formatCalendarDayRange('2026-10-30', '2026-11-02', 'ja')).toBe('10月30日（金）〜11月2日（月）'))
  it('年跨ぎ', () => expect(formatCalendarDayRange('2026-12-31', '2027-01-02', 'ja')).toBe('12月31日（木）〜1月2日（土）'))
  it('閏日', () => expect(formatCalendarDayRange('2028-02-28', '2028-03-01', 'ja')).toBe('2月28日（月）〜3月1日（水）'))
  it('時刻範囲', () => expect(formatTimeRange('09:00:00', '11:00:00', 'ja')).toBe('09:00〜11:00'))
  it('単一時刻', () => {
    expect(formatClockTime('9:00', 'ja')).toBe('09:00')
    expect(formatClockTime('23:00', 'ja')).toBe('23:00')
  })
})

describe('空・不正入力は例外にせず空文字（safeParse と同流儀）', () => {
  it.each(LOCALES)('%s', (loc) => {
    expect(formatCalendarDay('', loc)).toBe('')
    expect(formatCalendarDay(null, loc)).toBe('')
    expect(formatCalendarDay('abc', loc)).toBe('')
    expect(formatCalendarDayRange('', '2026-11-04', loc)).toBe('')
    expect(formatCalendarDayRange('2026-11-04', undefined, loc)).toBe('')
    expect(formatTimeRange('', '11:00', loc)).toBe('')
    expect(formatTimeRange('09:00', 'zz', loc)).toBe('')
    expect(formatClockTime('', loc)).toBe('')
  })
})
