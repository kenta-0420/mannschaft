import { describe, it, expect, beforeAll, beforeEach, vi } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import AdCreditLimitRequestsPage from '~/pages/admin/ad-credit-limit-requests.vue'

/**
 * CMP-260922-2045 第2陣 G4 の根治テスト。
 *
 * `pages/admin/ad-credit-limit-requests.vue` は与信枠増額申請一覧の取得失敗を握りつぶし、
 * `requests` を空配列にリセットするだけで「申請なし」の空状態へ落ちていた（トーストすら
 * 出ていなかった）。審査待ちキューであり「0件＝処理不要」の誤認は実害が大きいため、
 * エラー専用状態（DashboardErrorState / ad-credit-limit-requests-error-state）で修正した。
 *
 * 検証観点:
 *   AC-001 取得失敗時に ad-credit-limit-requests-error-state が描画される
 *   AC-002（対照）取得成功・0件時はエラー状態を出さない
 */

const adminGetCreditLimitRequests = vi.fn()

vi.mock('~/composables/useAdvertiserApi', () => ({
  useAdvertiserApi: () => ({
    adminGetCreditLimitRequests,
    adminApproveCreditLimitRequest: vi.fn(),
    adminRejectCreditLimitRequest: vi.fn(),
  }),
}))

const notificationMock = { success: vi.fn(), error: vi.fn(), warn: vi.fn(), info: vi.fn() }
vi.mock('~/composables/useNotification', () => ({ useNotification: () => notificationMock }))

beforeAll(async () => {
  adminGetCreditLimitRequests.mockResolvedValue({ data: [] })
  const warmup = await mountSuspended(AdCreditLimitRequestsPage)
  warmup.unmount()
})

describe('pages/admin/ad-credit-limit-requests.vue — 取得失敗時のエラー状態', () => {
  beforeEach(() => {
    adminGetCreditLimitRequests.mockReset()
    notificationMock.error.mockClear()
  })

  it('AC-001: 取得失敗時に ad-credit-limit-requests-error-state が描画される', async () => {
    adminGetCreditLimitRequests.mockRejectedValue(new Error('network error'))
    const wrapper = await mountSuspended(AdCreditLimitRequestsPage)
    await flushMicrotasks()

    expect(wrapper.find('[data-testid="ad-credit-limit-requests-error-state"]').exists()).toBe(true)
  })

  it('AC-002（対照）: 取得成功・0件時はエラー状態を出さない', async () => {
    adminGetCreditLimitRequests.mockResolvedValue({ data: [] })
    const wrapper = await mountSuspended(AdCreditLimitRequestsPage)
    await flushMicrotasks()

    expect(wrapper.find('[data-testid="ad-credit-limit-requests-error-state"]').exists()).toBe(false)
  })

  it('AC-003: 再試行は初回と同じ load() を呼ぶ', async () => {
    adminGetCreditLimitRequests.mockRejectedValueOnce(new Error('network error'))
    adminGetCreditLimitRequests.mockResolvedValueOnce({ data: [] })
    const wrapper = await mountSuspended(AdCreditLimitRequestsPage)
    await flushMicrotasks()

    expect(wrapper.find('[data-testid="ad-credit-limit-requests-error-state"]').exists()).toBe(true)
    await wrapper.find('[data-testid="ad-credit-limit-requests-error-state-retry"]').trigger('click')
    await flushMicrotasks()

    expect(adminGetCreditLimitRequests).toHaveBeenCalledTimes(2)
    expect(wrapper.find('[data-testid="ad-credit-limit-requests-error-state"]').exists()).toBe(false)
  })
})

async function flushMicrotasks() {
  for (let i = 0; i < 5; i++) {
    await Promise.resolve()
  }
}
