import { describe, it, expect, beforeAll, beforeEach, afterEach, vi } from 'vitest'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import CreationRequestsPage from '~/pages/admin/villages/creation-requests.vue'

/**
 * CMP-260922-2045 第2陣 G4 の根治テスト。
 *
 * `pages/admin/villages/creation-requests.vue` は村作成申請一覧の取得が失敗しても
 * `requests` を空配列にリセットするだけで、そのまま「申請がありません」の空状態へ
 * 落ちていた。権限エラー・通信断が「申請なし（=対応不要）」に誤認されると、審査待ち
 * 申請を見落とす実害があるため、エラー専用状態（DashboardErrorState /
 * creation-requests-error-state）で修正した。
 *
 * 検証観点:
 *   CR-001 取得失敗時に creation-requests-error-state が描画される
 *   CR-002（対照）取得成功・0件時はエラー状態を出さない
 */

const listAdminCreationRequests = vi.fn()
const reviewCreationRequest = vi.fn()

vi.mock('~/composables/useVillageApi', () => ({
  useVillageApi: () => ({ listAdminCreationRequests, reviewCreationRequest }),
}))

const notificationMock = { success: vi.fn(), error: vi.fn(), warn: vi.fn(), info: vi.fn() }
vi.mock('~/composables/useNotification', () => ({ useNotification: () => notificationMock }))

mockNuxtImport('useAuthStore', () => () => ({ isSystemAdmin: true }))

beforeAll(async () => {
  listAdminCreationRequests.mockResolvedValue({ content: [], totalElements: 0 })
  const warmup = await mountPage()
  warmup.unmount()
})

describe('pages/admin/villages/creation-requests.vue — 取得失敗時のエラー状態', () => {
  let wrapper: Awaited<ReturnType<typeof mountPage>> | undefined

  beforeEach(() => {
    listAdminCreationRequests.mockReset()
    notificationMock.error.mockClear()
  })

  afterEach(() => {
    wrapper?.unmount()
    wrapper = undefined
  })

  it('CR-001: 取得失敗時に creation-requests-error-state が描画される', async () => {
    listAdminCreationRequests.mockRejectedValue(new Error('network error'))
    wrapper = await mountPage()
    await flushMicrotasks()

    expect(wrapper.find('[data-testid="creation-requests-error-state"]').exists()).toBe(true)
  })

  it('CR-002（対照）: 取得成功・0件時はエラー状態を出さない', async () => {
    listAdminCreationRequests.mockResolvedValue({ content: [], totalElements: 0 })
    wrapper = await mountPage()
    await flushMicrotasks()

    expect(wrapper.find('[data-testid="creation-requests-error-state"]').exists()).toBe(false)
  })

  it('CR-003: 再試行は初回と同じ load() を呼ぶ', async () => {
    listAdminCreationRequests.mockRejectedValueOnce(new Error('network error'))
    listAdminCreationRequests.mockResolvedValueOnce({ content: [], totalElements: 0 })
    wrapper = await mountPage()
    await flushMicrotasks()

    expect(wrapper.find('[data-testid="creation-requests-error-state"]').exists()).toBe(true)
    await wrapper.find('[data-testid="creation-requests-error-state-retry"]').trigger('click')
    await flushMicrotasks()

    expect(listAdminCreationRequests).toHaveBeenCalledTimes(2)
    expect(wrapper.find('[data-testid="creation-requests-error-state"]').exists()).toBe(false)
  })
})

async function mountPage() {
  const wrapper = await mountSuspended(CreationRequestsPage)
  // PrimeVue TabList は mount の 150 ms 後に updateInkBar を実行し、unmount 時にもタイマーを解除しない。
  // jsdom の破棄前に実行させ、テスト終了後の未処理例外を防ぐ。
  await new Promise((resolve) => setTimeout(resolve, 200))
  return wrapper
}

async function flushMicrotasks() {
  for (let i = 0; i < 5; i++) {
    await Promise.resolve()
  }
}
