import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { flushPromises } from '@vue/test-utils'
import { useCalendarEvents, type CalendarEventItem } from './useCalendarEvents'

const event = (id: number, startAt: string, endAt: string): CalendarEventItem => ({
  id, uniqueKey: String(id), title: '予定', startAt, endAt, allDay: false, color: null, isPersonal: true,
})

beforeEach(() => {
  setActivePinia(createPinia())
  vi.useFakeTimers({ toFake: ['Date'] })
  vi.setSystemTime(new Date('2026-08-15T03:00:00Z'))
})
afterEach(() => vi.useRealTimers())

describe('日別モーダルの取得状態と範囲', () => {
  it('グリッド前に開始した複数日予定を残し、開始日の00:00に終了した予定は除外する', async () => {
    const fetcher = vi.fn().mockResolvedValue([
      event(1, '2026-07-24T10:00:00+09:00', '2026-08-02T00:00:00+09:00'),
      event(2, '2026-07-24T10:00:00+09:00', '2026-07-26T00:00:00+09:00'),
      event(3, '2026-07-25T20:00:00Z', '2026-07-25T21:00:00Z'),
      event(4, '2026-07-26T00:00:00+09:00', '2026-07-26T00:00:00+09:00'),
    ])
    const calendar = useCalendarEvents(fetcher)

    await calendar.loadEvents()

    expect(calendar.events.value.map(event => event.id)).toEqual([1, 3, 4])
  })

  it('取得失敗を公開し、再試行成功でエラーを解除する', async () => {
    const fetcher = vi.fn().mockRejectedValueOnce(new Error('offline')).mockResolvedValue([])
    const calendar = useCalendarEvents(fetcher)

    await calendar.loadEvents()
    expect(calendar.loadError.value).toBe(true)
    await calendar.loadEvents()

    expect(calendar.loadError.value).toBe(false)
    expect(calendar.loading.value).toBe(false)
  })

  it('月移動失敗と保存後再取得失敗も公開し、refreshWithResultの呼出単位契約を保つ', async () => {
    const failure = new Error('offline')
    const fetcher = vi.fn().mockResolvedValueOnce([]).mockRejectedValue(failure)
    const calendar = useCalendarEvents(fetcher, { cacheHalfMonths: 0 })
    await calendar.loadEvents()

    calendar.onNextMonth()
    await flushPromises()
    expect(calendar.loadError.value).toBe(true)
    expect(await calendar.refreshWithResult()).toEqual({ ok: false, error: failure })
    await calendar.refresh()
    expect(calendar.loadError.value).toBe(true)

    fetcher.mockResolvedValue([])
    expect(await calendar.refreshWithResult()).toEqual({ ok: true })
    expect(calendar.loadError.value).toBe(false)
  })
})
