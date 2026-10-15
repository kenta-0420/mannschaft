import { describe, it, expect, beforeAll, beforeEach, vi } from 'vitest'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import TeamInvoicesPage from '~/pages/teams/[slug]/advertiser/invoices.vue'

/**
 * CMP-260922-2045 第2陣 G5 の根治テスト（組織版と対になるチーム版）。
 *
 * `pages/teams/[slug]/advertiser/invoices.vue` は請求書取得が失敗しても
 * `invoices` を空配列にリセットするだけで、DataTable の既定「データがありません」表示に
 * そのまま落ちていた。エラー専用状態（DashboardErrorState / advertiser-invoices-error-state）で修正した。
 *
 * 検証観点:
 *   TI-001 取得失敗時に advertiser-invoices-error-state が描画される
 *   TI-002（対照）取得成功・0件時はエラー状態を出さない
 *   TI-003 再試行が初回表示と同じ取得関数（loadInvoices）を呼ぶ
 */

const getInvoices = vi.fn()

vi.mock('~/composables/useAdvertiserApi', () => ({
  useAdvertiserApi: () => ({
    getInvoices,
    getInvoiceDetail: vi.fn(),
    downloadInvoicePdf: vi.fn(),
  }),
}))

mockNuxtImport('useRoute', () => () => ({ params: { slug: 'team-1' } }))

beforeAll(async () => {
  getInvoices.mockResolvedValue({ data: [] })
  const warmup = await mountSuspended(TeamInvoicesPage)
  warmup.unmount()
})

describe('pages/teams/[slug]/advertiser/invoices.vue — 取得失敗時のエラー状態', () => {
  beforeEach(() => {
    getInvoices.mockReset()
  })

  it('TI-001: 取得失敗時に advertiser-invoices-error-state が描画される', async () => {
    getInvoices.mockRejectedValue(new Error('network error'))
    const wrapper = await mountSuspended(TeamInvoicesPage)
    await flushMicrotasks()

    expect(wrapper.find('[data-testid="advertiser-invoices-error-state"]').exists()).toBe(true)
  })

  it('TI-002（対照）: 取得成功・0件時はエラー状態を出さない', async () => {
    getInvoices.mockResolvedValue({ data: [] })
    const wrapper = await mountSuspended(TeamInvoicesPage)
    await flushMicrotasks()

    expect(wrapper.find('[data-testid="advertiser-invoices-error-state"]').exists()).toBe(false)
  })

  it('TI-003: 再試行が初回表示と同じ取得処理（getInvoices）を呼ぶ', async () => {
    getInvoices.mockRejectedValueOnce(new Error('network error'))
    getInvoices.mockResolvedValueOnce({ data: [] })
    const wrapper = await mountSuspended(TeamInvoicesPage)
    await flushMicrotasks()
    expect(wrapper.find('[data-testid="advertiser-invoices-error-state"]').exists()).toBe(true)

    await wrapper.find('[data-testid="advertiser-invoices-error-state-retry"]').trigger('click')
    await flushMicrotasks()

    expect(getInvoices).toHaveBeenCalledTimes(2)
    expect(wrapper.find('[data-testid="advertiser-invoices-error-state"]').exists()).toBe(false)
  })
})

async function flushMicrotasks() {
  for (let i = 0; i < 5; i++) {
    await Promise.resolve()
  }
}
