import { describe, it, expect, vi } from 'vitest'
import { ref, computed } from 'vue'

vi.stubGlobal('ref', ref)
vi.stubGlobal('computed', computed)
vi.stubGlobal('useUserSettingsApi', () => ({
  getProfile: () => Promise.resolve({ data: { countryCode: 'JP' } }),
}))

import { useHolidays } from './useHolidays'

describe('useHolidays.getHoliday', () => {
  // 2026-10-12 はスポーツの日（Sports Day）
  it('lang=ja で日本語名を返す', () => {
    const { getHoliday } = useHolidays()
    expect(getHoliday('2026-10-12', 'ja')).toBe('スポーツの日')
  })

  it('lang=de（JPデータに無い言語）では英語名にフォールバックする', () => {
    const { getHoliday } = useHolidays()
    expect(getHoliday('2026-10-12', 'de')).toBe('Sports Day')
  })

  it('lang=en で英語名を返す', () => {
    const { getHoliday } = useHolidays()
    expect(getHoliday('2026-10-12', 'en')).toBe('Sports Day')
  })

  it('祝日でない日は null', () => {
    const { getHoliday } = useHolidays()
    expect(getHoliday('2026-10-13', 'de')).toBeNull()
  })
})
