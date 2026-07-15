import { describe, it, expect, beforeEach, vi } from 'vitest'

/**
 * F17.1 村機能 useVillageEventApi — 歳時記カレンダー契約テスト
 *
 * 背景: FE `useVillageEventApi.ts` はかつて一覧レスポンスを配列と誤宣言しており、
 * `events.value` にエンベロープオブジェクトが代入され `v-for` がゴミ描画する実害があった。
 * BE の真の応答形状 `{items, year, month}`（`CalendarEventListResponse`）をモックし、
 * FE がこれを正しく展開できることを固定する。
 *
 * 検証観点:
 *   CAL-API-001: listCalendarEvents は GET .../calendar-events?year=&month= を呼び、
 *                `{items, year, month}` エンベロープ（BE の真の形状）をそのまま返す
 *   CAL-API-002: params 未指定時はクエリなしで呼ぶ（BE 側が現在年月にフォールバック）
 *   CAL-API-003: items が空配列でも正しく `{items: [], year, month}` を返す（空状態描画の土台）
 */

const mockFetch = vi.fn()

vi.mock('~/composables/useApi', () => ({
  useApi: () => mockFetch,
}))

// eslint-disable-next-line import/first
import { useVillageEventApi } from '~/composables/village/useVillageEventApi'

const VILLAGE = 'v-uuid-1'

describe('useVillageEventApi — 歳時記カレンダー', () => {
  beforeEach(() => {
    mockFetch.mockReset()
  })

  it('CAL-API-001: listCalendarEvents は year/month クエリで {items, year, month} を返す', async () => {
    const envelope = {
      items: [
        {
          id: 'ev-1',
          villageId: VILLAGE,
          title: '田植え祭り',
          description: null,
          eventDate: '2026-07-10',
          eventEndDate: null,
          isAnnualRecurring: true,
          iconEmoji: '🌾',
          colorHex: null,
          createdByUserId: 1,
          createdByDisplayName: null,
          createdAt: '2026-01-01T00:00:00Z',
        },
      ],
      year: 2026,
      month: 7,
    }
    mockFetch.mockResolvedValueOnce({ data: envelope })

    const api = useVillageEventApi()
    const res = await api.listCalendarEvents(VILLAGE, { year: 2026, month: 7 })

    expect(mockFetch).toHaveBeenCalledWith(
      `/api/v1/villages/${VILLAGE}/calendar-events?year=2026&month=7`,
    )
    // BE 契約どおり items/year/month を持つオブジェクトであり、配列ではない
    expect(Array.isArray(res)).toBe(false)
    expect(res.items).toHaveLength(1)
    expect(res.year).toBe(2026)
    expect(res.month).toBe(7)
  })

  it('CAL-API-002: params 未指定時はクエリなしで呼ぶ', async () => {
    mockFetch.mockResolvedValueOnce({ data: { items: [], year: 2026, month: 7 } })

    const api = useVillageEventApi()
    await api.listCalendarEvents(VILLAGE)

    expect(mockFetch).toHaveBeenCalledWith(
      `/api/v1/villages/${VILLAGE}/calendar-events`,
    )
  })

  it('CAL-API-003: items が空配列でも {items: [], year, month} を維持する（ゴミ描画防止の土台）', async () => {
    mockFetch.mockResolvedValueOnce({ data: { items: [], year: 2026, month: 8 } })

    const api = useVillageEventApi()
    const res = await api.listCalendarEvents(VILLAGE, { year: 2026, month: 8 })

    expect(res.items).toEqual([])
    expect(res.items.length).toBe(0)
  })
})
