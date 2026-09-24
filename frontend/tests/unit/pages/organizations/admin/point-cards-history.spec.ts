import { describe, it, expect, beforeAll, beforeEach, vi } from 'vitest'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import PointCardsHistoryPage from '~/pages/organizations/[slug]/admin/point-cards/history.vue'

/**
 * CMP-260922-2045 第2陣 G3 の根治テスト。
 *
 * `pages/organizations/[slug]/admin/point-cards/history.vue` はスタンプ履歴
 * （listOrgStamps）取得失敗時も `stamps` を空配列にリセットするだけで、
 * `StampHistoryTable` の 0 件文言（wallet.admin.history.empty）へそのまま
 * 落ちていた。エラー専用状態（DashboardErrorState /
 * point-cards-stamp-history-error-state）で修正した。
 *
 * 検証観点:
 *   PCH-001 取得失敗時に point-cards-stamp-history-error-state が描画される
 *   PCH-002（対照）取得成功・0件時はエラー状態を出さない
 *   PCH-003 再試行は初回と同じ取得処理（listOrgStamps）を呼ぶ
 */

const listProviders = vi.fn()
const listOrgStamps = vi.fn()
const listOrgBalanceEvents = vi.fn()

mockNuxtImport('useRoute', () => () => ({ params: { slug: 'org-1' } }))
mockNuxtImport('useI18n', () => () => ({ t: (key: string) => key }))
mockNuxtImport('useOrgWalletApi', () => () => ({
  listProviders,
  listOrgStamps,
  listOrgBalanceEvents,
}))

const orgStoreStub = {
  myOrganizations: [{ id: 'org-1', role: 'ADMIN' }],
  fetchMyOrganizations: vi.fn(async () => {}),
}
mockNuxtImport('useOrganizationStore', () => () => orgStoreStub)

beforeAll(async () => {
  listProviders.mockResolvedValue([])
  listOrgStamps.mockResolvedValue({ content: [], totalElements: 0, totalPages: 0 })
  listOrgBalanceEvents.mockResolvedValue({ content: [], totalElements: 0, totalPages: 0 })
  const warmup = await mountSuspended(PointCardsHistoryPage)
  warmup.unmount()
})

describe('pages/organizations/[slug]/admin/point-cards/history.vue — 取得失敗時のエラー状態', () => {
  beforeEach(() => {
    listProviders.mockReset()
    listOrgStamps.mockReset()
    listProviders.mockResolvedValue([])
  })

  it('PCH-001: 取得失敗時に point-cards-stamp-history-error-state が描画される', async () => {
    listOrgStamps.mockRejectedValue(new Error('network error'))
    const wrapper = await mountSuspended(PointCardsHistoryPage)
    await flushMicrotasks()

    expect(wrapper.find('[data-testid="point-cards-stamp-history-error-state"]').exists()).toBe(true)
  })

  it('PCH-002（対照）: 取得成功・0件時はエラー状態を出さない', async () => {
    listOrgStamps.mockResolvedValue({ content: [], totalElements: 0, totalPages: 0 })
    const wrapper = await mountSuspended(PointCardsHistoryPage)
    await flushMicrotasks()

    expect(wrapper.find('[data-testid="point-cards-stamp-history-error-state"]').exists()).toBe(false)
  })

  it('PCH-003: 再試行は初回と同じ取得処理を呼ぶ', async () => {
    listOrgStamps.mockRejectedValueOnce(new Error('network error'))
    const wrapper = await mountSuspended(PointCardsHistoryPage)
    await flushMicrotasks()
    expect(wrapper.find('[data-testid="point-cards-stamp-history-error-state"]').exists()).toBe(true)

    listOrgStamps.mockResolvedValueOnce({ content: [], totalElements: 0, totalPages: 0 })
    const callsBefore = listOrgStamps.mock.calls.length
    await wrapper.find('[data-testid="point-cards-stamp-history-error-state-retry"]').trigger('click')
    await flushMicrotasks()

    expect(listOrgStamps.mock.calls.length).toBe(callsBefore + 1)
    expect(wrapper.find('[data-testid="point-cards-stamp-history-error-state"]').exists()).toBe(false)
  })
})

async function flushMicrotasks() {
  for (let i = 0; i < 5; i++) {
    await Promise.resolve()
  }
}
