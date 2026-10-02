import { describe, it, expect, beforeAll, beforeEach, vi } from 'vitest'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import ActionMemoClosingPage from '~/pages/action-memo/closing.vue'

/**
 * CMP-260922-2045 第2陣 G1 の根治テスト。
 *
 * `pages/action-memo/closing.vue` の中部 TODO セクション（完了/未完）は
 * `loadTodos` が失敗しても `todos` を空配列にリセットするだけで、
 * 「完了した TODO はありません」「未完の TODO はありません」という空状態文言に
 * そのまま落ちていた。権限エラー・通信断が「TODO なし」に誤読される欠陥を、
 * エラー専用状態（DashboardErrorState / action-memo-closing-todos-error-state）で修正した。
 *
 * 検証観点:
 *   AMC-001 TODO 取得失敗時に action-memo-closing-todos-error-state が描画される
 *   AMC-002（対照）取得成功・0件時は通常の空状態文言が出て、エラー状態は出ない
 *   AMC-003 再試行は初回表示と同じ取得関数（getMyTodos）を呼ぶ
 */

const getMyTodos = vi.fn()

vi.mock('~/composables/useTodoApi', () => ({
  useTodoApi: () => ({ getMyTodos }),
}))
vi.mock('~/composables/useApi', () => ({
  useApi: () => vi.fn(),
}))

const notificationMock = { success: vi.fn(), error: vi.fn(), warn: vi.fn(), info: vi.fn() }
vi.mock('~/composables/useNotification', () => ({ useNotification: () => notificationMock }))

const actionMemoStoreStub = {
  currentDayMemos: () => [],
  error: null,
  loading: false,
  settings: { defaultPostTeamId: null },
  fetchSettings: vi.fn(async () => {}),
  fetchMemosForDate: vi.fn(async () => {}),
  fetchAvailableTeams: vi.fn(async () => {}),
}
mockNuxtImport('useActionMemoStore', () => () => actionMemoStoreStub)

const authStoreStub = { user: { timezone: 'Asia/Tokyo' } }
mockNuxtImport('useAuthStore', () => () => authStoreStub)

beforeAll(async () => {
  getMyTodos.mockResolvedValue({ data: [] })
  const warmup = await mountSuspended(ActionMemoClosingPage)
  warmup.unmount()
})

describe('pages/action-memo/closing.vue — 取得失敗時のエラー状態', () => {
  beforeEach(() => {
    getMyTodos.mockReset()
    notificationMock.error.mockClear()
  })

  it('AMC-001: TODO 取得失敗時に action-memo-closing-todos-error-state が描画される', async () => {
    getMyTodos.mockRejectedValue(new Error('network error'))
    const wrapper = await mountSuspended(ActionMemoClosingPage)
    await flushMicrotasks()

    expect(wrapper.find('[data-testid="action-memo-closing-todos-error-state"]').exists()).toBe(true)
  })

  it('AMC-002（対照）: 取得成功・0件時はエラー状態を出さない', async () => {
    getMyTodos.mockResolvedValue({ data: [] })
    const wrapper = await mountSuspended(ActionMemoClosingPage)
    await flushMicrotasks()

    expect(wrapper.find('[data-testid="action-memo-closing-todos-error-state"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="action-memo-closing-completed-todos"]').exists()).toBe(false)
  })

  it('AMC-003: 再試行は初回表示と同じ取得関数（getMyTodos）を呼ぶ', async () => {
    getMyTodos.mockRejectedValueOnce(new Error('network error'))
    const wrapper = await mountSuspended(ActionMemoClosingPage)
    await flushMicrotasks()
    expect(getMyTodos).toHaveBeenCalledTimes(1)

    getMyTodos.mockResolvedValueOnce({ data: [] })
    const retryButton = wrapper.find('[data-testid="action-memo-closing-todos-error-state-retry"]')
    expect(retryButton.exists()).toBe(true)
    await retryButton.trigger('click')
    await flushMicrotasks()

    expect(getMyTodos).toHaveBeenCalledTimes(2)
    expect(wrapper.find('[data-testid="action-memo-closing-todos-error-state"]').exists()).toBe(false)
  })
})

async function flushMicrotasks() {
  for (let i = 0; i < 5; i++) {
    await Promise.resolve()
  }
}
