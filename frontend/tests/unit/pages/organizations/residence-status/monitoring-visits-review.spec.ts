import { describe, it, expect, beforeAll, beforeEach, vi } from 'vitest'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import type { DOMWrapper } from '@vue/test-utils'
import { ref } from 'vue'
import MonitoringVisitsReviewPage from '~/pages/organizations/[slug]/residence-status/monitoring-visits-review.vue'

/**
 * CMP-260922-2045 第2陣 G3 の根治テスト。
 *
 * `pages/organizations/[slug]/residence-status/monitoring-visits-review.vue` は
 * 訪問履歴検索が失敗しても `visits` を空配列にリセットするだけで、`searched` が true の
 * ままテーブルの空状態（「該当する訪問記録がありません」＝0件文言）へそのまま落ちていた。
 * 権限エラー・通信断が「該当なし」に誤読される欠陥を、エラー専用状態
 * （DashboardErrorState / monitoring-visits-review-error-state）で修正した。
 *
 * 検証観点:
 *   MV-001 検索失敗時に monitoring-visits-review-error-state が描画される
 *   MV-002（対照）検索成功・0件時は「該当する訪問記録がありません」文言が出て、エラー状態は出ない
 *   MV-003 再試行は初回と同じ検索処理（handleSearch）を呼ぶ
 */

const listVisitsByCommittee = vi.fn()
const listVisitsByResident = vi.fn()

mockNuxtImport('useRoute', () => () => ({ params: { slug: 'org-1' } }))
mockNuxtImport('useMonitoringVisitApi', () => () => ({
  listVisitsByCommittee,
  listVisitsByResident,
}))
mockNuxtImport('useDatetime', () => () => ({ formatDate: (d: string) => d, userTimezone: ref('Asia/Tokyo') }))

beforeAll(async () => {
  const warmup = await mountSuspended(MonitoringVisitsReviewPage)
  warmup.unmount()
})

async function search(wrapper: Awaited<ReturnType<typeof mountSuspended>>) {
  await wrapper.find('input[type="number"]').setValue('1')
  // 委員会IDモード既定で検索ボタンをクリックする
  const searchButtons = wrapper.findAll('button').filter((b: DOMWrapper<Element>) => b.text().includes('検索'))
  await searchButtons[0]!.trigger('click')
  await flushMicrotasks()
}

describe('pages/organizations/[slug]/residence-status/monitoring-visits-review.vue — 取得失敗時のエラー状態', () => {
  beforeEach(() => {
    listVisitsByCommittee.mockReset()
    listVisitsByResident.mockReset()
  })

  it('MV-001: 検索失敗時に monitoring-visits-review-error-state が描画される', async () => {
    listVisitsByCommittee.mockRejectedValue(new Error('network error'))
    const wrapper = await mountSuspended(MonitoringVisitsReviewPage)
    await search(wrapper)

    expect(wrapper.find('[data-testid="monitoring-visits-review-error-state"]').exists()).toBe(true)
  })

  it('MV-002（対照）: 検索成功・0件時はエラー状態を出さず0件文言が出る', async () => {
    listVisitsByCommittee.mockResolvedValue({ data: [] })
    const wrapper = await mountSuspended(MonitoringVisitsReviewPage)
    await search(wrapper)

    expect(wrapper.find('[data-testid="monitoring-visits-review-error-state"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('該当する訪問記録がありません')
  })

  it('MV-003: 再試行は初回と同じ検索処理を呼ぶ', async () => {
    listVisitsByCommittee.mockRejectedValueOnce(new Error('network error'))
    const wrapper = await mountSuspended(MonitoringVisitsReviewPage)
    await search(wrapper)
    expect(wrapper.find('[data-testid="monitoring-visits-review-error-state"]').exists()).toBe(true)

    listVisitsByCommittee.mockResolvedValueOnce({ data: [] })
    const callsBefore = listVisitsByCommittee.mock.calls.length
    await wrapper.find('[data-testid="monitoring-visits-review-error-state-retry"]').trigger('click')
    await flushMicrotasks()

    expect(listVisitsByCommittee.mock.calls.length).toBe(callsBefore + 1)
    expect(wrapper.find('[data-testid="monitoring-visits-review-error-state"]').exists()).toBe(false)
  })
})

async function flushMicrotasks() {
  for (let i = 0; i < 5; i++) {
    await Promise.resolve()
  }
}
