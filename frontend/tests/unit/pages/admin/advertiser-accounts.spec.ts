import { describe, it, expect, beforeAll, beforeEach, vi } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import AdvertiserAccountsPage from '~/pages/admin/advertiser-accounts.vue'

/**
 * CMP-260922-2045 第2陣 G4 の根治テスト。
 *
 * `pages/admin/advertiser-accounts.vue` は広告主アカウント一覧の取得失敗を握りつぶし、
 * `accounts` を空配列にリセットするだけで「アカウントなし」の空状態へ落ちていた。
 * 審査待ちアカウントを含むキューであり「0件＝処理不要」の誤認は実害が大きいため、
 * エラー専用状態（DashboardErrorState / advertiser-accounts-error-state）で修正した。
 *
 * 検証観点:
 *   AA-001 取得失敗時に advertiser-accounts-error-state が描画される
 *   AA-002（対照）取得成功・0件時はエラー状態を出さない
 */

const adminGetAdvertiserAccounts = vi.fn()

vi.mock('~/composables/useAdvertiserApi', () => ({
  useAdvertiserApi: () => ({
    adminGetAdvertiserAccounts,
    adminApproveAccount: vi.fn(),
    adminSuspendAccount: vi.fn(),
    adminUpdateCreditLimit: vi.fn(),
  }),
}))

const notificationMock = { success: vi.fn(), error: vi.fn(), warn: vi.fn(), info: vi.fn() }
vi.mock('~/composables/useNotification', () => ({ useNotification: () => notificationMock }))

beforeAll(async () => {
  adminGetAdvertiserAccounts.mockResolvedValue({ data: [] })
  const warmup = await mountSuspended(AdvertiserAccountsPage)
  warmup.unmount()
})

describe('pages/admin/advertiser-accounts.vue — 取得失敗時のエラー状態', () => {
  beforeEach(() => {
    adminGetAdvertiserAccounts.mockReset()
    notificationMock.error.mockClear()
  })

  it('AA-001: 取得失敗時に advertiser-accounts-error-state が描画される', async () => {
    adminGetAdvertiserAccounts.mockRejectedValue(new Error('network error'))
    const wrapper = await mountSuspended(AdvertiserAccountsPage)
    await flushMicrotasks()

    expect(wrapper.find('[data-testid="advertiser-accounts-error-state"]').exists()).toBe(true)
  })

  it('AA-002（対照）: 取得成功・0件時はエラー状態を出さない', async () => {
    adminGetAdvertiserAccounts.mockResolvedValue({ data: [] })
    const wrapper = await mountSuspended(AdvertiserAccountsPage)
    await flushMicrotasks()

    expect(wrapper.find('[data-testid="advertiser-accounts-error-state"]').exists()).toBe(false)
  })

  it('AA-003: 再試行は初回と同じ load() を呼ぶ', async () => {
    adminGetAdvertiserAccounts.mockRejectedValueOnce(new Error('network error'))
    adminGetAdvertiserAccounts.mockResolvedValueOnce({ data: [] })
    const wrapper = await mountSuspended(AdvertiserAccountsPage)
    await flushMicrotasks()

    expect(wrapper.find('[data-testid="advertiser-accounts-error-state"]').exists()).toBe(true)
    await wrapper.find('[data-testid="advertiser-accounts-error-state-retry"]').trigger('click')
    await flushMicrotasks()

    expect(adminGetAdvertiserAccounts).toHaveBeenCalledTimes(2)
    expect(wrapper.find('[data-testid="advertiser-accounts-error-state"]').exists()).toBe(false)
  })
})

async function flushMicrotasks() {
  for (let i = 0; i < 5; i++) {
    await Promise.resolve()
  }
}
