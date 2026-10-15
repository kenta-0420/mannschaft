import { describe, it, expect, beforeAll, beforeEach, vi } from 'vitest'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import MyJobsIndexPage from '~/pages/me/jobs/index.vue'

/**
 * CMP-260922-2045 の根治テスト。
 *
 * `pages/me/jobs/index.vue` は応募一覧の取得失敗時に `applications` を空配列に
 * リセットするだけで、そのまま「応募履歴がありません」の空状態へ落ちていた。
 * エラー専用状態（DashboardErrorState / my-applications-error-state）で修正した。
 *
 * 検証観点:
 *   MJ-001 応募一覧の取得失敗時に my-applications-error-state が描画される
 *   MJ-002（対照）取得成功・0件時はエラー状態を出さない
 */

const listMyApplications = vi.fn()
const withdrawApplication = vi.fn()
const listMyContracts = vi.fn()

vi.mock('~/composables/jobs/useJobApplicationApi', () => ({
  useJobApplicationApi: () => ({ listMyApplications, withdrawApplication }),
}))
vi.mock('~/composables/jobs/useJobContractApi', () => ({
  useJobContractApi: () => ({
    listMyContracts,
    reportCompletion: vi.fn(),
    approveCompletion: vi.fn(),
    rejectCompletion: vi.fn(),
    cancelContract: vi.fn(),
  }),
}))

const notificationMock = { success: vi.fn(), error: vi.fn(), warn: vi.fn(), info: vi.fn() }
vi.mock('~/composables/useNotification', () => ({ useNotification: () => notificationMock }))

mockNuxtImport('useAuthStore', () => () => ({ user: { id: 1 } }))

beforeAll(async () => {
  listMyApplications.mockResolvedValue({ data: [], meta: { total: 0, page: 0, size: 20, totalPages: 0 } })
  const warmup = await mountSuspended(MyJobsIndexPage)
  warmup.unmount()
})

describe('pages/me/jobs/index.vue — 取得失敗時のエラー状態', () => {
  beforeEach(() => {
    listMyApplications.mockReset()
    notificationMock.error.mockClear()
  })

  it('MJ-001: 応募一覧の取得失敗時に my-applications-error-state が描画される', async () => {
    listMyApplications.mockRejectedValue(new Error('network error'))
    const wrapper = await mountSuspended(MyJobsIndexPage)
    await flushMicrotasks()

    expect(wrapper.find('[data-testid="my-applications-error-state"]').exists()).toBe(true)
  })

  it('MJ-002（対照）: 取得成功・0件時はエラー状態を出さない', async () => {
    listMyApplications.mockResolvedValue({ data: [], meta: { total: 0, page: 0, size: 20, totalPages: 0 } })
    const wrapper = await mountSuspended(MyJobsIndexPage)
    await flushMicrotasks()

    expect(wrapper.find('[data-testid="my-applications-error-state"]').exists()).toBe(false)
  })
})

async function flushMicrotasks() {
  for (let i = 0; i < 5; i++) {
    await Promise.resolve()
  }
}
