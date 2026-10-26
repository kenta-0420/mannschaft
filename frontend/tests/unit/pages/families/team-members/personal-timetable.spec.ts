import { describe, it, expect, beforeAll, beforeEach, vi } from 'vitest'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import FamilyPersonalTimetableIndexPage from '~/pages/families/[teamId]/members/[userId]/personal-timetable/index.vue'

/**
 * CMP-260922-2045 第2陣 G1 の根治テスト。
 *
 * `pages/families/[teamId]/members/[userId]/personal-timetable/index.vue` は家族閲覧用の
 * 個人時間割一覧の取得が失敗しても `items` を空配列にリセットするだけで、空状態文言
 * （personalTimetable.familyView.list_empty）へそのまま落ちていた。権限エラー・通信断が
 * 「未登録」に誤読される欠陥を、エラー専用状態（DashboardErrorState /
 * family-personal-timetable-error-state）で修正した。
 *
 * 検証観点:
 *   FPT-001 取得失敗時に family-personal-timetable-error-state が描画される
 *   FPT-002（対照）取得成功・0件時は通常の空状態文言が出て、エラー状態は出ない
 *   FPT-003 再試行は初回表示と同じ取得関数（list）を呼ぶ
 */

const list = vi.fn()

vi.mock('~/composables/useFamilyPersonalTimetableApi', () => ({
  useFamilyPersonalTimetableApi: () => ({ list }),
}))

const notificationMock = { success: vi.fn(), error: vi.fn(), warn: vi.fn(), info: vi.fn() }
vi.mock('~/composables/useNotification', () => ({ useNotification: () => notificationMock }))

mockNuxtImport('useRoute', () => () => ({ params: { teamId: 'team-1', userId: '5' } }))

beforeAll(async () => {
  list.mockResolvedValue([])
  const warmup = await mountSuspended(FamilyPersonalTimetableIndexPage)
  warmup.unmount()
})

describe('pages/families/[teamId]/members/[userId]/personal-timetable/index.vue — 取得失敗時のエラー状態', () => {
  beforeEach(() => {
    list.mockReset()
    notificationMock.error.mockClear()
  })

  it('FPT-001: 取得失敗時に family-personal-timetable-error-state が描画される', async () => {
    list.mockRejectedValue(new Error('network error'))
    const wrapper = await mountSuspended(FamilyPersonalTimetableIndexPage)
    await flushMicrotasks()

    expect(wrapper.find('[data-testid="family-personal-timetable-error-state"]').exists()).toBe(true)
  })

  it('FPT-002（対照）: 取得成功・0件時はエラー状態を出さない', async () => {
    list.mockResolvedValue([])
    const wrapper = await mountSuspended(FamilyPersonalTimetableIndexPage)
    await flushMicrotasks()

    expect(wrapper.find('[data-testid="family-personal-timetable-error-state"]').exists()).toBe(false)
  })

  it('FPT-003: 再試行は初回表示と同じ取得関数（list）を呼ぶ', async () => {
    list.mockRejectedValueOnce(new Error('network error'))
    const wrapper = await mountSuspended(FamilyPersonalTimetableIndexPage)
    await flushMicrotasks()
    expect(list).toHaveBeenCalledTimes(1)

    list.mockResolvedValueOnce([])
    const retryButton = wrapper.find('[data-testid="family-personal-timetable-error-state-retry"]')
    expect(retryButton.exists()).toBe(true)
    await retryButton.trigger('click')
    await flushMicrotasks()

    expect(list).toHaveBeenCalledTimes(2)
    expect(wrapper.find('[data-testid="family-personal-timetable-error-state"]').exists()).toBe(false)
  })
})

async function flushMicrotasks() {
  for (let i = 0; i < 5; i++) {
    await Promise.resolve()
  }
}
