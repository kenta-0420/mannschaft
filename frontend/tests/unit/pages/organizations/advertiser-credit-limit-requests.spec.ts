import { describe, it, expect, beforeAll, beforeEach, vi } from 'vitest'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import OrgCreditLimitRequestsPage from '~/pages/organizations/[slug]/advertiser/credit-limit-requests.vue'

/**
 * CMP-260922-2045 第2陣 G5 の根治テスト。
 *
 * `pages/organizations/[slug]/advertiser/credit-limit-requests.vue` は与信枠増額申請一覧の
 * 取得が失敗しても `requests` を空配列にリセットするだけで、DataTable の既定
 * 「データがありません」表示にそのまま落ちていた。エラー専用状態
 * （DashboardErrorState / credit-limit-requests-error-state）で修正した。
 *
 * 検証観点:
 *   OC-001 取得失敗時に credit-limit-requests-error-state が描画される
 *   OC-002（対照）取得成功・0件時はエラー状態を出さない
 *   OC-003 再試行が初回表示と同じ取得関数（load）を呼ぶ
 */

const getCreditLimitRequests = vi.fn()

vi.mock('~/composables/useAdvertiserApi', () => ({
  useAdvertiserApi: () => ({
    getCreditLimitRequests,
    createCreditLimitRequest: vi.fn(),
  }),
}))

const notificationMock = { success: vi.fn(), error: vi.fn(), warn: vi.fn(), info: vi.fn() }
vi.mock('~/composables/useNotification', () => ({ useNotification: () => notificationMock }))

mockNuxtImport('useRoute', () => () => ({ params: { slug: 'org-1' } }))

beforeAll(async () => {
  getCreditLimitRequests.mockResolvedValue({ data: [] })
  const warmup = await mountSuspended(OrgCreditLimitRequestsPage)
  warmup.unmount()
})

describe('pages/organizations/[slug]/advertiser/credit-limit-requests.vue — 取得失敗時のエラー状態', () => {
  beforeEach(() => {
    getCreditLimitRequests.mockReset()
  })

  it('OC-001: 取得失敗時に credit-limit-requests-error-state が描画される', async () => {
    getCreditLimitRequests.mockRejectedValue(new Error('network error'))
    const wrapper = await mountSuspended(OrgCreditLimitRequestsPage)
    await flushMicrotasks()

    expect(wrapper.find('[data-testid="credit-limit-requests-error-state"]').exists()).toBe(true)
  })

  it('OC-002（対照）: 取得成功・0件時はエラー状態を出さない', async () => {
    getCreditLimitRequests.mockResolvedValue({ data: [] })
    const wrapper = await mountSuspended(OrgCreditLimitRequestsPage)
    await flushMicrotasks()

    expect(wrapper.find('[data-testid="credit-limit-requests-error-state"]').exists()).toBe(false)
  })

  it('OC-003: 再試行が初回表示と同じ取得処理（getCreditLimitRequests）を呼ぶ', async () => {
    getCreditLimitRequests.mockRejectedValueOnce(new Error('network error'))
    getCreditLimitRequests.mockResolvedValueOnce({ data: [] })
    const wrapper = await mountSuspended(OrgCreditLimitRequestsPage)
    await flushMicrotasks()
    expect(wrapper.find('[data-testid="credit-limit-requests-error-state"]').exists()).toBe(true)

    await wrapper.find('[data-testid="credit-limit-requests-error-state-retry"]').trigger('click')
    await flushMicrotasks()

    expect(getCreditLimitRequests).toHaveBeenCalledTimes(2)
    expect(wrapper.find('[data-testid="credit-limit-requests-error-state"]').exists()).toBe(false)
  })
})

async function flushMicrotasks() {
  for (let i = 0; i < 5; i++) {
    await Promise.resolve()
  }
}
