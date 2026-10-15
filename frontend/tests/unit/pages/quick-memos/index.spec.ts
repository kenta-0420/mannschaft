import { describe, it, expect, beforeAll, beforeEach, vi } from 'vitest'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import QuickMemosIndexPage from '~/pages/quick-memos/index.vue'

/**
 * CMP-260922-2045 第2陣 G1 の根治テスト。
 *
 * `pages/quick-memos/index.vue` はメモ一覧の取得が失敗すると通知は出すものの
 * `memos` を更新しない（初回は空配列のまま）ため、空状態文言（quick_memo.no_memos）へ
 * そのまま落ちていた。権限エラー・通信断が「メモなし」に誤読される欠陥を、
 * エラー専用状態（DashboardErrorState / quick-memos-error-state）で修正した。
 *
 * 検証観点:
 *   QM-001 取得失敗時に quick-memos-error-state が描画される
 *   QM-002（対照）取得成功・0件時は通常の空状態文言が出て、エラー状態は出ない
 *   QM-003 再試行は初回表示と同じ取得関数（listMemos）を呼ぶ
 */

const listMemos = vi.fn()
const searchMemos = vi.fn()
const listTags = vi.fn()

vi.mock('~/composables/useQuickMemoApi', () => ({
  useQuickMemoApi: () => ({ listMemos, searchMemos }),
}))
vi.mock('~/composables/useTagApi', () => ({
  useTagApi: () => ({ listTags }),
}))

const notificationMock = { success: vi.fn(), error: vi.fn(), warn: vi.fn(), info: vi.fn() }
vi.mock('~/composables/useNotification', () => ({ useNotification: () => notificationMock }))

mockNuxtImport('useRoute', () => () => ({ query: {} }))

beforeAll(async () => {
  listMemos.mockResolvedValue({ data: [], meta: { totalPages: 1, unsortedCount: 0 } })
  listTags.mockResolvedValue({ data: [] })
  const warmup = await mountSuspended(QuickMemosIndexPage)
  warmup.unmount()
})

describe('pages/quick-memos/index.vue — 取得失敗時のエラー状態', () => {
  beforeEach(() => {
    listMemos.mockReset()
    listTags.mockReset()
    listTags.mockResolvedValue({ data: [] })
    notificationMock.error.mockClear()
  })

  it('QM-001: 取得失敗時に quick-memos-error-state が描画される', async () => {
    listMemos.mockRejectedValue(new Error('network error'))
    const wrapper = await mountSuspended(QuickMemosIndexPage)
    await flushMicrotasks()

    expect(wrapper.find('[data-testid="quick-memos-error-state"]').exists()).toBe(true)
  })

  it('QM-002（対照）: 取得成功・0件時はエラー状態を出さない', async () => {
    listMemos.mockResolvedValue({ data: [], meta: { totalPages: 1, unsortedCount: 0 } })
    const wrapper = await mountSuspended(QuickMemosIndexPage)
    await flushMicrotasks()

    expect(wrapper.find('[data-testid="quick-memos-error-state"]').exists()).toBe(false)
  })

  it('QM-003: 再試行は初回表示と同じ取得関数（listMemos）を呼ぶ', async () => {
    listMemos.mockRejectedValueOnce(new Error('network error'))
    const wrapper = await mountSuspended(QuickMemosIndexPage)
    await flushMicrotasks()
    expect(listMemos).toHaveBeenCalledTimes(1)

    listMemos.mockResolvedValueOnce({ data: [], meta: { totalPages: 1, unsortedCount: 0 } })
    const retryButton = wrapper.find('[data-testid="quick-memos-error-state-retry"]')
    expect(retryButton.exists()).toBe(true)
    await retryButton.trigger('click')
    await flushMicrotasks()

    expect(listMemos).toHaveBeenCalledTimes(2)
    expect(wrapper.find('[data-testid="quick-memos-error-state"]').exists()).toBe(false)
  })
})

async function flushMicrotasks() {
  for (let i = 0; i < 5; i++) {
    await Promise.resolve()
  }
}
