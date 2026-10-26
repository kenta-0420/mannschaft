import { beforeEach, describe, it, expect, vi } from 'vitest'
import { ref, computed } from 'vue'
import { useHolidays } from './useHolidays'

const { constructorSpy } = vi.hoisted(() => ({ constructorSpy: vi.fn() }))
vi.mock('date-holidays', async (importOriginal) => {
  const { default: RealHolidays } = await importOriginal<typeof import('date-holidays')>()
  return {
    default: class extends RealHolidays {
      constructor(...args: ConstructorParameters<typeof RealHolidays>) {
        super(...args)
        constructorSpy(...args)
      }
    },
  }
})

vi.stubGlobal('ref', ref)
vi.stubGlobal('computed', computed)
vi.stubGlobal('useUserSettingsApi', () => ({
  getProfile: () => Promise.resolve({ data: { countryCode: 'JP' } }),
}))

describe('useHolidays.getHoliday', () => {
  beforeEach(() => constructorSpy.mockClear())

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

  it('同じ国と言語で42日を判定してもHolidaysは1回だけ生成する', () => {
    const { getHoliday } = useHolidays()
    for (let day = 0; day < 42; day++) {
      const date = new Date(2026, 9, day + 1)
      const dateStr = [date.getFullYear(), date.getMonth() + 1, date.getDate()]
        .map((value, index) => index === 0 ? String(value) : String(value).padStart(2, '0'))
        .join('-')
      getHoliday(dateStr, 'ja')
    }
    expect(constructorSpy).toHaveBeenCalledTimes(1)
  })

  it('国既定言語と英語を交互に判定しても各1回だけ生成する', () => {
    const { getHoliday } = useHolidays()
    for (let i = 0; i < 42; i++) {
      getHoliday('2026-10-12')
      expect(getHoliday('2026-10-12', 'en')).toBe('Sports Day')
    }
    expect(constructorSpy).toHaveBeenCalledTimes(2)
  })

  it('言語を切り替えても日本語・英語・既定言語を混同しない', () => {
    const { getHoliday } = useHolidays()
    expect(getHoliday('2026-10-12', 'ja')).toBe('スポーツの日')
    expect(getHoliday('2026-10-12', 'en')).toBe('Sports Day')
    expect(getHoliday('2026-10-12', 'ja')).toBe('スポーツの日')
    expect(getHoliday('2026-10-13', 'ja')).toBeNull()
    expect(constructorSpy).toHaveBeenCalledTimes(2)
  })

  it('国コードが変わった後は新しい国の祝日で判定する', () => {
    // Vitest の import.meta.client は false なので、API 確定後の ref 更新を再現する。
    const selectedCountry = ref<string | null | undefined>(undefined)
    vi.stubGlobal('ref', () => selectedCountry)
    try {
      const { getHoliday, countryCode } = useHolidays()
      expect(getHoliday('2026-04-29', 'en')).toBe('Showa Day')
      selectedCountry.value = 'US'
      expect(countryCode.value).toBe('US')
      expect(getHoliday('2026-04-29', 'en')).toBeNull()
      expect(constructorSpy).toHaveBeenCalledTimes(2)
    } finally {
      vi.stubGlobal('ref', ref)
    }
  })
})
