import { describe, it, expect, beforeAll, beforeEach, vi } from 'vitest'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import OperationalQueuePage from '~/pages/system-admin/advertising/operational-queue.vue'

/**
 * CMP-260922-2045 第2陣 G4 の根治テスト。
 *
 * `pages/system-admin/advertising/operational-queue.vue` は審査待ちキャンペーン
 * 一覧の取得失敗時にトーストは出すものの、`items` を空配列にリセットするだけで
 * 「審査待ちのキャンペーンはありません」の空状態へ落ちていた。審査待ちキューであり
 * 「0件＝処理不要」の誤認は実害が大きいため、エラー専用状態（DashboardErrorState /
 * operational-queue-error-state）で修正した。
 *
 * 検証観点:
 *   OQ-001 取得失敗時に operational-queue-error-state が描画される
 *   OQ-002（対照）取得成功・0件時はエラー状態を出さない
 */

const listQueue = vi.fn()

vi.mock('~/composables/useSystemAdminOperationalCampaignApi', () => ({
  useSystemAdminOperationalCampaignApi: () => ({
    listQueue,
    getDetail: vi.fn(),
    approve: vi.fn(),
    reject: vi.fn(),
  }),
}))

const notificationMock = { success: vi.fn(), error: vi.fn(), warn: vi.fn(), info: vi.fn() }
vi.mock('~/composables/useNotification', () => ({ useNotification: () => notificationMock }))
mockNuxtImport('useConfirm', () => () => ({ require: vi.fn() }))

beforeAll(async () => {
  listQueue.mockResolvedValue({ data: [], meta: { total: 0 } })
  const warmup = await mountSuspended(OperationalQueuePage)
  warmup.unmount()
})

describe('pages/system-admin/advertising/operational-queue.vue — 取得失敗時のエラー状態', () => {
  beforeEach(() => {
    listQueue.mockReset()
    notificationMock.error.mockClear()
  })

  it('OQ-001: 取得失敗時に operational-queue-error-state が描画される', async () => {
    listQueue.mockRejectedValue(new Error('network error'))
    const wrapper = await mountSuspended(OperationalQueuePage)
    await flushMicrotasks()

    expect(wrapper.find('[data-testid="operational-queue-error-state"]').exists()).toBe(true)
  })

  it('OQ-002（対照）: 取得成功・0件時はエラー状態を出さない', async () => {
    listQueue.mockResolvedValue({ data: [], meta: { total: 0 } })
    const wrapper = await mountSuspended(OperationalQueuePage)
    await flushMicrotasks()

    expect(wrapper.find('[data-testid="operational-queue-error-state"]').exists()).toBe(false)
  })

  it('OQ-003: 再試行は初回と同じ load() を呼ぶ', async () => {
    listQueue.mockRejectedValueOnce(new Error('network error'))
    listQueue.mockResolvedValueOnce({ data: [], meta: { total: 0 } })
    const wrapper = await mountSuspended(OperationalQueuePage)
    await flushMicrotasks()

    expect(wrapper.find('[data-testid="operational-queue-error-state"]').exists()).toBe(true)
    await wrapper.find('[data-testid="operational-queue-error-state-retry"]').trigger('click')
    await flushMicrotasks()

    expect(listQueue).toHaveBeenCalledTimes(2)
    expect(wrapper.find('[data-testid="operational-queue-error-state"]').exists()).toBe(false)
  })
})

async function flushMicrotasks() {
  for (let i = 0; i < 5; i++) {
    await Promise.resolve()
  }
}
