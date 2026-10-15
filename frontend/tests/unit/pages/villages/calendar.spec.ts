import { describe, it, expect, beforeAll, beforeEach, vi } from 'vitest'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import CalendarPage from '~/pages/villages/[id]/calendar.vue'

/**
 * CMP-260922-2045 第2陣 G2 の根治テスト。
 *
 * `pages/villages/[id]/calendar.vue`（歳時記）は月別行事の取得失敗時に `events` を
 * 空配列にリセットするだけで、空状態（「今月の行事はありません」）をそのまま描画していた。
 * エラー専用状態（DashboardErrorState / village-calendar-error-state）で修正した。
 *
 * 検証観点:
 *   CA-001 取得失敗時に village-calendar-error-state が描画される
 *   CA-002（対照）取得成功・0件時はエラー状態を出さない
 *   CA-003 再試行が初回表示と同じ取得関数（loadEvents）を呼ぶ
 */

const listCalendarEvents = vi.fn()

vi.mock('~/composables/useVillageApi', () => ({
  useVillageApi: () => ({ listCalendarEvents }),
}))

vi.mock('~/composables/useVillageContext', () => ({
  useVillageContext: () => ({
    village: { value: { id: 'v1', name: 'テスト村' } },
    perms: { value: { isMember: true, isAdmin: false, isHeadman: false, myRole: 'VILLAGER' } },
    currentUserId: { value: 1 },
    refresh: vi.fn(async () => {}),
    myMembership: { value: null },
    openEditDialog: vi.fn(),
  }),
}))

const errorHandlerMock = { handleApiError: vi.fn() }
vi.mock('~/composables/useErrorHandler', () => ({ useErrorHandler: () => errorHandlerMock }))

const notificationMock = { success: vi.fn(), error: vi.fn(), warn: vi.fn(), info: vi.fn() }
vi.mock('~/composables/useNotification', () => ({ useNotification: () => notificationMock }))

vi.mock('~/composables/useConfirmDialog', () => ({
  useConfirmDialog: () => ({ confirmAction: vi.fn() }),
}))

mockNuxtImport('useRoute', () => () => ({ params: { id: 'v1' } }))

beforeAll(async () => {
  listCalendarEvents.mockResolvedValue({ items: [] })
  const warmup = await mountSuspended(CalendarPage)
  warmup.unmount()
})

describe('pages/villages/[id]/calendar.vue — 取得失敗時のエラー状態', () => {
  beforeEach(() => {
    listCalendarEvents.mockReset()
    errorHandlerMock.handleApiError.mockClear()
  })

  it('CA-001: 取得失敗時に village-calendar-error-state が描画される', async () => {
    listCalendarEvents.mockRejectedValue(new Error('network error'))
    const wrapper = await mountSuspended(CalendarPage)
    await flushMicrotasks()

    expect(wrapper.find('[data-testid="village-calendar-error-state"]').exists()).toBe(true)
  })

  it('CA-002（対照）: 取得成功・0件時はエラー状態を出さない', async () => {
    listCalendarEvents.mockResolvedValue({ items: [] })
    const wrapper = await mountSuspended(CalendarPage)
    await flushMicrotasks()

    expect(wrapper.find('[data-testid="village-calendar-error-state"]').exists()).toBe(false)
  })

  it('CA-003: 再試行が初回表示と同じ取得関数を呼ぶ（成功に回復できる）', async () => {
    listCalendarEvents.mockRejectedValueOnce(new Error('network error'))
    listCalendarEvents.mockResolvedValueOnce({
      items: [{ id: 'e1', title: 'X', eventDate: '2026-01-01', isAnnualRecurring: false }],
    })
    const wrapper = await mountSuspended(CalendarPage)
    await flushMicrotasks()
    expect(wrapper.find('[data-testid="village-calendar-error-state"]').exists()).toBe(true)

    await wrapper.find('[data-testid="village-calendar-error-state-retry"]').trigger('click')
    await flushMicrotasks()

    expect(listCalendarEvents).toHaveBeenCalledTimes(2)
    expect(wrapper.find('[data-testid="village-calendar-error-state"]').exists()).toBe(false)
  })
})

async function flushMicrotasks() {
  for (let i = 0; i < 5; i++) {
    await Promise.resolve()
  }
}
