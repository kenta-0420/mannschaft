import { describe, expect, it, vi } from 'vitest'
import { ref } from 'vue'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import CalendarGrid from './CalendarGrid.vue'

const { localeRef } = vi.hoisted(() => ({ localeRef: { value: 'ja' } }))
mockNuxtImport('useI18n', () => () => ({
  t: (key: string) => key,
  locale: ref(localeRef.value),
}))
// 祝日は date-holidays（国コードは API 取得）に依存するため、テストでは API を呼ばない固定実装にする。
mockNuxtImport('useHolidays', () => () => ({
  getHoliday: () => null,
  countryCode: ref('JP'),
}))

const JAPANESE = /[ぁ-んァ-ヶ一-龥]/

async function render(locale: string) {
  localeRef.value = locale
  const wrapper = await mountSuspended(CalendarGrid, { props: { year: 2026, month: 10, events: [] } })
  const heading = wrapper.find('h2').text()
  const weekdayCells = wrapper.findAll('div.grid.grid-cols-7 > div.text-center.text-xs.font-medium').slice(0, 7)
  return { heading, weekdays: weekdayCells.map((c) => c.text()) }
}

describe('CalendarGrid 曜日・年月の i18n', () => {
  it('ja: 現行どおり「2026年10月」と 日〜土', async () => {
    const { heading, weekdays } = await render('ja')
    expect(heading).toBe('2026年10月')
    expect(weekdays).toEqual(['日', '月', '火', '水', '木', '金', '土'])
  })

  it('en: 日本語が出ず英語の月名・曜日になる', async () => {
    const { heading, weekdays } = await render('en')
    expect(heading).toBe('October 2026')
    expect(weekdays).toEqual(['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'])
    expect(heading + weekdays.join('')).not.toMatch(JAPANESE)
  })

  it('de: 日本語が出ずドイツ語になる', async () => {
    const { heading, weekdays } = await render('de')
    expect(heading).toMatch(/Oktober 2026/)
    expect(weekdays).toHaveLength(7)
    expect(heading + weekdays.join('')).not.toMatch(JAPANESE)
  })
})
