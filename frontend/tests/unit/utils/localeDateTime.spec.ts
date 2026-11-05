import { describe, it, expect } from 'vitest'
import {
  formatCalendarDay,
  formatCalendarDayRange,
  formatClockTime,
  formatInstantDate,
  formatInstantDateTime,
  formatInstantTime,
  formatTimeRange,
  parseInstantStrict,
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

describe('不正入力は別の値に化けず空文字を返す（Codex 再検分 P2）', () => {
  const badDays = ['2026-02-30', '2026-11-04junk', '2026-13-01', '2025-02-29', '2026-00-10', '2026-04-31', '2026-11-00', ' 2026-11-04']
  for (const bad of badDays) {
    it(`formatCalendarDay(${JSON.stringify(bad)}) は ''`, () => {
      expect(formatCalendarDay(bad, 'ja')).toBe('')
      expect(formatCalendarDay(bad, 'en')).toBe('')
    })
    it(`formatCalendarDayRange(${JSON.stringify(bad)}) は ''`, () => {
      expect(formatCalendarDayRange(bad, '2026-11-05', 'ja')).toBe('')
      expect(formatCalendarDayRange('2026-11-05', bad, 'en')).toBe('')
    })
  }
  it('閏年 2024-02-29 は正常', () => {
    expect(formatCalendarDay('2024-02-29', 'ja')).toBe('2月29日（木）')
  })
  const badTimes = ['25:00', '24:00', '12:60', '09:00junk', '9:5', '09:00:60', '09:00:7', '-1:00']
  for (const bad of badTimes) {
    it(`formatClockTime(${JSON.stringify(bad)}) は ''`, () => {
      expect(formatClockTime(bad, 'ja')).toBe('')
      expect(formatTimeRange(bad, '10:00', 'ja')).toBe('')
      expect(formatTimeRange('10:00', bad, 'en')).toBe('')
    })
  }
  it('正常な時刻は従来どおり', () => {
    expect(formatClockTime('9:05', 'ja')).toBe('09:05')
    expect(formatClockTime('23:59:59', 'ja')).toBe('23:59')
    expect(formatClockTime('00:00', 'ja')).toBe('00:00')
  })
})

describe('不正な Date / 不正な ISO 文字列は例外にも補正にもならず空文字（Codex 再確認 P2）', () => {
  it('Invalid Date は formatInstant* が RangeError を投げず \'\'', () => {
    const bad = new Date(NaN)
    expect(formatInstantDate(bad, 'ja', 'Asia/Tokyo')).toBe('')
    expect(formatInstantTime(bad, 'en', 'Asia/Tokyo')).toBe('')
    expect(formatInstantDateTime(bad, 'de', 'UTC')).toBe('')
  })
  it('Date でない値も \'\'', () => {
    expect(formatInstantDate('2026-05-22' as unknown as Date, 'ja', 'Asia/Tokyo')).toBe('')
    expect(formatInstantDateTime(null as unknown as Date, 'ja', 'Asia/Tokyo')).toBe('')
  })
  it('正常な Date は従来どおり', () => {
    expect(formatInstantDateTime(new Date('2026-05-22T00:15:20Z'), 'ja', 'Asia/Tokyo')).toBe('2026/05/22 09:15')
  })
  it('parseInstantStrict: 不正は null、正常な Instant 形式は通る', () => {
    for (const bad of ['2026-02-30', '2026-02-30T25:00:00', '2026-11-04junk', '2026-11-04T24:00:00', '2026-11-04T10:60:00', '2026-11-04T10:00:00+25:00', '2025-02-29T10:00:00Z', '']) {
      expect(parseInstantStrict(bad), bad).toBeNull()
    }
    expect(parseInstantStrict('2026-05-22T09:15:20+09:00')!.toISOString()).toBe('2026-05-22T00:15:20.000Z')
    expect(parseInstantStrict('2026-05-22T00:15:20Z')!.toISOString()).toBe('2026-05-22T00:15:20.000Z')
    expect(parseInstantStrict('2026-05-22T00:15:20.123Z')!.toISOString()).toBe('2026-05-22T00:15:20.123Z')
    expect(parseInstantStrict('2024-02-29T00:00:00Z')!.toISOString()).toBe('2024-02-29T00:00:00.000Z')
  })
})
