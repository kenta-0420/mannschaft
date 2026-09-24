import { describe, it, expect, beforeAll, beforeEach, vi } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import PersonalTimetableIndexPage from '~/pages/me/personal-timetable/index.vue'

/**
 * CMP-260922-2045 第2陣 G1 の根治テスト。
 *
 * `pages/me/personal-timetable/index.vue` は個人時間割一覧の取得が失敗しても
 * `items` を空配列にリセットするだけで、空状態文言（personalTimetable.list_empty）
 * へそのまま落ちていた。権限エラー・通信断が「未登録」に誤読される欠陥を、
 * エラー専用状態（DashboardErrorState / personal-timetable-error-state）で修正した。
 *
 * 検証観点:
 *   PT-001 取得失敗時に personal-timetable-error-state が描画される
 *   PT-002（対照）取得成功・0件時は通常の空状態文言が出て、エラー状態は出ない
 *   PT-003 再試行は初回表示と同じ取得関数（list）を呼ぶ
 */

const list = vi.fn()

vi.mock('~/composables/useMyPersonalTimetableApi', () => ({
  useMyPersonalTimetableApi: () => ({ list }),
}))

const notificationMock = { success: vi.fn(), error: vi.fn(), warn: vi.fn(), info: vi.fn() }
vi.mock('~/composables/useNotification', () => ({ useNotification: () => notificationMock }))

beforeAll(async () => {
  list.mockResolvedValue([])
  const warmup = await mountSuspended(PersonalTimetableIndexPage)
  warmup.unmount()
})

describe('pages/me/personal-timetable/index.vue — 取得失敗時のエラー状態', () => {
  beforeEach(() => {
    list.mockReset()
    notificationMock.error.mockClear()
  })

  it('PT-001: 取得失敗時に personal-timetable-error-state が描画される', async () => {
    list.mockRejectedValue(new Error('network error'))
    const wrapper = await mountSuspended(PersonalTimetableIndexPage)
    await flushMicrotasks()

    expect(wrapper.find('[data-testid="personal-timetable-error-state"]').exists()).toBe(true)
  })

  it('PT-002（対照）: 取得成功・0件時はエラー状態を出さない', async () => {
    list.mockResolvedValue([])
    const wrapper = await mountSuspended(PersonalTimetableIndexPage)
    await flushMicrotasks()

    expect(wrapper.find('[data-testid="personal-timetable-error-state"]').exists()).toBe(false)
  })

  it('PT-003: 再試行は初回表示と同じ取得関数（list）を呼ぶ', async () => {
    list.mockRejectedValueOnce(new Error('network error'))
    const wrapper = await mountSuspended(PersonalTimetableIndexPage)
    await flushMicrotasks()
    expect(list).toHaveBeenCalledTimes(1)

    list.mockResolvedValueOnce([])
    const retryButton = wrapper.find('[data-testid="personal-timetable-error-state-retry"]')
    expect(retryButton.exists()).toBe(true)
    await retryButton.trigger('click')
    await flushMicrotasks()

    expect(list).toHaveBeenCalledTimes(2)
    expect(wrapper.find('[data-testid="personal-timetable-error-state"]').exists()).toBe(false)
  })
})

async function flushMicrotasks() {
  for (let i = 0; i < 5; i++) {
    await Promise.resolve()
  }
}
