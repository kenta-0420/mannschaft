import { describe, it, expect, beforeAll, beforeEach, vi } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import AdRateCardsPage from '~/pages/admin/ad-rate-cards.vue'

/**
 * CMP-260922-2045 第2陣 G4 の根治テスト。
 *
 * `pages/admin/ad-rate-cards.vue` は料金カード一覧の取得失敗を握りつぶし、
 * `rateCards` を空配列にリセットするだけで「カードなし」の空状態へ落ちていた。
 * エラー専用状態（DashboardErrorState / ad-rate-cards-error-state）で修正した。
 *
 * 検証観点:
 *   RC-001 取得失敗時に ad-rate-cards-error-state が描画される
 *   RC-002（対照）取得成功・0件時はエラー状態を出さない
 */

const adminGetRateCards = vi.fn()

vi.mock('~/composables/useAdvertiserApi', () => ({
  useAdvertiserApi: () => ({
    adminGetRateCards,
    adminCreateRateCard: vi.fn(),
    adminDeleteRateCard: vi.fn(),
  }),
}))

const notificationMock = { success: vi.fn(), error: vi.fn(), warn: vi.fn(), info: vi.fn() }
vi.mock('~/composables/useNotification', () => ({ useNotification: () => notificationMock }))

beforeAll(async () => {
  adminGetRateCards.mockResolvedValue({ data: [] })
  const warmup = await mountSuspended(AdRateCardsPage)
  warmup.unmount()
})

describe('pages/admin/ad-rate-cards.vue — 取得失敗時のエラー状態', () => {
  beforeEach(() => {
    adminGetRateCards.mockReset()
    notificationMock.error.mockClear()
  })

  it('RC-001: 取得失敗時に ad-rate-cards-error-state が描画される', async () => {
    adminGetRateCards.mockRejectedValue(new Error('network error'))
    const wrapper = await mountSuspended(AdRateCardsPage)
    await flushMicrotasks()

    expect(wrapper.find('[data-testid="ad-rate-cards-error-state"]').exists()).toBe(true)
  })

  it('RC-002（対照）: 取得成功・0件時はエラー状態を出さない', async () => {
    adminGetRateCards.mockResolvedValue({ data: [] })
    const wrapper = await mountSuspended(AdRateCardsPage)
    await flushMicrotasks()

    expect(wrapper.find('[data-testid="ad-rate-cards-error-state"]').exists()).toBe(false)
  })

  it('RC-003: 再試行は初回と同じ load() を呼ぶ', async () => {
    adminGetRateCards.mockRejectedValueOnce(new Error('network error'))
    adminGetRateCards.mockResolvedValueOnce({ data: [] })
    const wrapper = await mountSuspended(AdRateCardsPage)
    await flushMicrotasks()

    expect(wrapper.find('[data-testid="ad-rate-cards-error-state"]').exists()).toBe(true)
    await wrapper.find('[data-testid="ad-rate-cards-error-state-retry"]').trigger('click')
    await flushMicrotasks()

    expect(adminGetRateCards).toHaveBeenCalledTimes(2)
    expect(wrapper.find('[data-testid="ad-rate-cards-error-state"]').exists()).toBe(false)
  })
})

async function flushMicrotasks() {
  for (let i = 0; i < 5; i++) {
    await Promise.resolve()
  }
}
