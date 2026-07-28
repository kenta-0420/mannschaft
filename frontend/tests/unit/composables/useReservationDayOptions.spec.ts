import { describe, it, expect } from 'vitest'
import { isValidHalfHourRange, hmToMinutes, toHm, RESERVATION_DAY_OPTIONS } from '~/composables/useReservationDayOptions'

/**
 * useReservationDayOptions.ts ユニットテスト — 番人。
 *
 * 最重要観点（F03.4.5 §4.3・定期予約不可枠 W2-2-FE AC④）: 全日型（start/end 未指定）を作らせない設計判断の
 * 根拠となる `isValidHalfHourRange` を直接検証する。WeeklyScheduleManager.vue の定期予約不可
 * ダイアログは本関数の結果で保存ボタンを disabled にし、UI 上も show-clear を付けないため
 * 時刻を空にする経路そのものが無い（component側は「show-clearを持たない」構造テストで補完）。
 */
describe('isValidHalfHourRange（F03.4.5 §4.3: 全日型拒否の根拠）', () => {
  it('start < end の正常な半開区間は true', () => {
    expect(isValidHalfHourRange('19:00', '20:00')).toBe(true)
    expect(isValidHalfHourRange('00:00', '00:30')).toBe(true)
  })

  it('start と end が等しい場合は false（ゼロ幅は許容しない）', () => {
    expect(isValidHalfHourRange('19:00', '19:00')).toBe(false)
  })

  it('start > end（逆転）は false', () => {
    expect(isValidHalfHourRange('20:00', '19:00')).toBe(false)
  })

  it('片方または両方が空/未指定（全日型）は false', () => {
    expect(isValidHalfHourRange('', '20:00')).toBe(false)
    expect(isValidHalfHourRange('19:00', '')).toBe(false)
    expect(isValidHalfHourRange(null, null)).toBe(false)
    expect(isValidHalfHourRange(undefined, undefined)).toBe(false)
    expect(isValidHalfHourRange('', '')).toBe(false)
  })
})

describe('hmToMinutes / toHm（時刻ヘルパー）', () => {
  it('hmToMinutes: HH:mm を分に変換する', () => {
    expect(hmToMinutes('09:30')).toBe(570)
    expect(hmToMinutes('00:00')).toBe(0)
  })

  it('toHm: BE の秒付き表現を HH:mm に丸める。値なしは空文字', () => {
    expect(toHm('19:00:00')).toBe('19:00')
    expect(toHm(null)).toBe('')
    expect(toHm(undefined)).toBe('')
  })
})

describe('RESERVATION_DAY_OPTIONS（曜日選択肢・3文字大文字正準）', () => {
  it('7曜日すべてが3文字大文字コードで揃っている（MONDAY 等フルネームは含まない）', () => {
    const values = RESERVATION_DAY_OPTIONS.map(d => d.value)
    expect(values).toEqual(['SUN', 'MON', 'TUE', 'WED', 'THU', 'FRI', 'SAT'])
    for (const v of values) {
      expect(v).toMatch(/^[A-Z]{3}$/)
    }
  })
})
