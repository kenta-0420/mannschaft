import { describe, it, expect, beforeAll, beforeEach, vi } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import BatchesPage from '~/pages/system-admin/batches.vue'

/**
 * CMP-260922-2045 第2陣 G4 の根治テスト。
 *
 * `pages/system-admin/batches.vue` はバッチ一覧の取得失敗時にトーストは出すものの、
 * `batches` を空配列にリセットするだけで「バッチが登録されていません」の空状態へ
 * 落ちていた。エラー専用状態（DashboardErrorState / batches-error-state）で修正した。
 *
 * 検証観点:
 *   BA-001 取得失敗時に batches-error-state が描画される
 *   BA-002（対照）取得成功・0件時はエラー状態を出さない
 */

const listBatches = vi.fn()

vi.mock('~/composables/useSystemAdminBatchApi', () => ({
  useSystemAdminBatchApi: () => ({
    listBatches,
    trigger: vi.fn(),
    getStatus: vi.fn(),
  }),
}))

const notificationMock = { success: vi.fn(), error: vi.fn(), warn: vi.fn(), info: vi.fn() }
vi.mock('~/composables/useNotification', () => ({ useNotification: () => notificationMock }))

beforeAll(async () => {
  listBatches.mockResolvedValue({ data: [] })
  const warmup = await mountSuspended(BatchesPage)
  warmup.unmount()
})

describe('pages/system-admin/batches.vue — 取得失敗時のエラー状態', () => {
  beforeEach(() => {
    listBatches.mockReset()
    notificationMock.error.mockClear()
  })

  it('BA-001: 取得失敗時に batches-error-state が描画される', async () => {
    listBatches.mockRejectedValue(new Error('network error'))
    const wrapper = await mountSuspended(BatchesPage)
    await flushMicrotasks()

    expect(wrapper.find('[data-testid="batches-error-state"]').exists()).toBe(true)
  })

  it('BA-002（対照）: 取得成功・0件時はエラー状態を出さない', async () => {
    listBatches.mockResolvedValue({ data: [] })
    const wrapper = await mountSuspended(BatchesPage)
    await flushMicrotasks()

    expect(wrapper.find('[data-testid="batches-error-state"]').exists()).toBe(false)
  })

  it('BA-003: 再試行は初回と同じ load() を呼ぶ', async () => {
    listBatches.mockRejectedValueOnce(new Error('network error'))
    listBatches.mockResolvedValueOnce({ data: [] })
    const wrapper = await mountSuspended(BatchesPage)
    await flushMicrotasks()

    expect(wrapper.find('[data-testid="batches-error-state"]').exists()).toBe(true)
    await wrapper.find('[data-testid="batches-error-state-retry"]').trigger('click')
    await flushMicrotasks()

    expect(listBatches).toHaveBeenCalledTimes(2)
    expect(wrapper.find('[data-testid="batches-error-state"]').exists()).toBe(false)
  })
})

async function flushMicrotasks() {
  for (let i = 0; i < 5; i++) {
    await Promise.resolve()
  }
}
