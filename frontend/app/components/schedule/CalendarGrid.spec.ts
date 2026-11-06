// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { nextTick, ref } from 'vue'
import { mount } from '@vue/test-utils'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import dayjs from 'dayjs'
import utc from 'dayjs/plugin/utc'
import timezone from 'dayjs/plugin/timezone'
import CalendarGrid from './CalendarGrid.vue'

// Nuxt appを起動せず、Gridが使うplugins/dayjs.tsと同じ日時拡張を初期化する。
dayjs.extend(utc)
dayjs.extend(timezone)

const { localeRef, countryRef, holidaySpy } = vi.hoisted(() => ({
  localeRef: { value: 'ja', active: null as { value: string } | null },
  countryRef: { active: null as { value: string } | null },
  holidaySpy: vi.fn<(date: string, lang: string | undefined, country: string) => string | null>(),
}))
mockNuxtImport('useI18n', () => () => ({
  t: (key: string) => key,
  locale: localeRef.active ??= ref(localeRef.value),
}))
mockNuxtImport('useDatetime', () => () => ({ userTimezone: ref('Asia/Tokyo') }))
// 描画の再計算境界を検証する。実ライブラリの祝日規則・profile APIは別の試験で担保する。
mockNuxtImport('useHolidays', () => () => {
  const countryCode = countryRef.active ??= ref('JP')
  return {
    getHoliday: (date: string, lang?: string) => holidaySpy(date, lang, countryCode.value),
    countryCode,
  }
})

const mountedGrids: Array<{ unmount: () => void }> = []
async function mountGrid() {
  const wrapper = mount(CalendarGrid, {
    props: { year: 2026, month: 10, events: [] },
    global: {
      mocks: { $t: (key: string) => key },
      stubs: { Button: true, Popover: true, ScheduleTargetAudience: true },
    },
  })
  mountedGrids.push(wrapper)
  return wrapper
}

beforeEach(() => {
  localeRef.value = 'ja'
  localeRef.active = null
  countryRef.active = null
  holidaySpy.mockReset().mockReturnValue(null)
})
afterEach(() => {
  mountedGrids.forEach(wrapper => wrapper.unmount())
  mountedGrids.length = 0
})

const JAPANESE = /[ぁ-んァ-ヶ一-龥]/

async function render(locale: string) {
  localeRef.value = locale
  const wrapper = await mountGrid()
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

describe('CalendarGrid 42日分の祝日結果再利用', () => {
  function givenHolidays() {
    holidaySpy.mockImplementation((date, lang, country) => {
      if (country !== 'JP') return null
      if (date === '2026-10-12') return lang === 'en' ? 'Sports Day' : 'スポーツの日'
      if (date === '2026-11-03') return lang === 'en' ? 'Culture Day' : '文化の日'
      if (date === '2027-01-01') return lang === 'en' ? 'New Year' : '元日'
      return null
    })
  }

  it('初回は日付と言語ごとに1回まで、イベント再描画では追加判定せず祝日と予定を表示する', async () => {
    givenHolidays()
    const wrapper = await mountGrid()
    const holidayCell = wrapper.findAll('div[tabindex="-1"]').find(cell => cell.find('.inline-flex').text() === '12')!
    expect(wrapper.text()).toContain('スポーツの日')
    expect(holidayCell.find('.text-red-500').exists()).toBe(true)
    const callsByDateAndLanguage = new Map<string, number>()
    holidaySpy.mock.calls.forEach(([date, lang]) => {
      const key = JSON.stringify([date, lang ?? null])
      callsByDateAndLanguage.set(key, (callsByDateAndLanguage.get(key) ?? 0) + 1)
    })
    expect([...callsByDateAndLanguage.values()].every(count => count === 1)).toBe(true)
    expect(holidaySpy.mock.calls.length).toBeLessThanOrEqual(84)
    holidaySpy.mockClear()

    await wrapper.setProps({ events: [{
      id: 1, uniqueKey: 'test-event', title: '試験予定',
      startAt: '2026-10-12T09:00:00+09:00', endAt: '2026-10-12T10:00:00+09:00',
      allDay: false, isPersonal: true, scopeType: 'PERSONAL', color: null,
    }] })

    expect(wrapper.text()).toContain('試験予定')
    expect(wrapper.text()).toContain('スポーツの日')
    expect(holidaySpy).not.toHaveBeenCalled()
  })

  it('localeと国の後着変更で表示を更新し、既定言語の色判定とnullを分離する', async () => {
    givenHolidays()
    const wrapper = await mountGrid()
    const holidayCell = wrapper.findAll('div[tabindex="-1"]').find(cell => cell.find('.inline-flex').text() === '12')!
    holidaySpy.mockClear()
    localeRef.active!.value = 'en'
    await nextTick()
    expect(wrapper.text()).toContain('Sports Day')
    expect(wrapper.text()).not.toContain('スポーツの日')
    expect(holidaySpy.mock.calls.length).toBeLessThanOrEqual(84)

    holidaySpy.mockClear()
    countryRef.active!.value = 'US'
    await nextTick()
    expect(wrapper.text()).not.toContain('Sports Day')
    expect(holidayCell.find('.text-red-500').exists()).toBe(false)
    expect(holidaySpy.mock.calls.length).toBeLessThanOrEqual(84)

    holidaySpy.mockImplementation((date, lang) => date === '2026-10-12' && lang === undefined ? '国既定の祝日' : null)
    countryRef.active!.value = 'JP'
    await nextTick()
    expect(holidayCell.find('.text-red-500').exists()).toBe(true)
    expect(wrapper.text()).not.toContain('国既定の祝日')
    expect(wrapper.text()).not.toContain('Sports Day')
  })

  it('月変更と年境界では42日表を更新し、前の月の結果を流用しない', async () => {
    givenHolidays()
    const wrapper = await mountGrid()
    holidaySpy.mockClear()
    await wrapper.setProps({ month: 11 })
    expect(wrapper.text()).toContain('文化の日')
    expect(wrapper.text()).not.toContain('スポーツの日')
    expect(holidaySpy).toHaveBeenCalledWith('2026-11-03', 'ja', 'JP')
    expect(holidaySpy.mock.calls.length).toBeLessThanOrEqual(84)

    holidaySpy.mockClear()
    await wrapper.setProps({ month: 12 })
    expect(wrapper.text()).toContain('元日')
    expect(holidaySpy).toHaveBeenCalledWith('2027-01-01', 'ja', 'JP')
    expect(holidaySpy.mock.calls.length).toBeLessThanOrEqual(84)
  })
})
