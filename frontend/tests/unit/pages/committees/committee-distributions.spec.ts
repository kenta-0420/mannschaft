import { describe, it, expect, beforeAll, beforeEach, vi } from 'vitest'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import { ref } from 'vue'
import DistributionsPage from '~/pages/committees/[id]/distributions.vue'

/**
 * CMP-260922-2045 第2陣 G3 の根治テスト。
 *
 * `pages/committees/[id]/distributions.vue` は配布履歴取得が失敗しても
 * `distributions` を空配列にリセットするだけで、空状態（「配布履歴はありません」）へ
 * そのまま落ちていた。エラー専用状態（DashboardErrorState / committee-distributions-error-state）
 * で修正した。
 *
 * 検証観点:
 *   CD-001 取得失敗時に committee-distributions-error-state が描画される
 *   CD-002（対照）取得成功・0件時はエラー状態を出さない
 *   CD-003 再試行は初回と同じ取得処理（listDistributions）を呼ぶ
 */

const listDistributions = vi.fn()

mockNuxtImport('useRoute', () => () => ({ params: { id: '1' } }))
mockNuxtImport('useCommitteeApi', () => () => ({ listDistributions }))
mockNuxtImport('useErrorHandler', () => () => ({ handleApiError: vi.fn() }))
mockNuxtImport('useDatetime', () => () => ({ userTimezone: ref('Asia/Tokyo') }))

beforeAll(async () => {
  listDistributions.mockResolvedValue({ data: [] })
  const warmup = await mountSuspended(DistributionsPage)
  warmup.unmount()
})

describe('pages/committees/[id]/distributions.vue — 取得失敗時のエラー状態', () => {
  beforeEach(() => {
    listDistributions.mockReset()
  })

  it('CD-001: 取得失敗時に committee-distributions-error-state が描画される', async () => {
    listDistributions.mockRejectedValue(new Error('network error'))
    const wrapper = await mountSuspended(DistributionsPage)
    await flushMicrotasks()

    expect(wrapper.find('[data-testid="committee-distributions-error-state"]').exists()).toBe(true)
  })

  it('CD-002（対照）: 取得成功・0件時はエラー状態を出さない', async () => {
    listDistributions.mockResolvedValue({ data: [] })
    const wrapper = await mountSuspended(DistributionsPage)
    await flushMicrotasks()

    expect(wrapper.find('[data-testid="committee-distributions-error-state"]').exists()).toBe(false)
  })

  it('CD-003: 再試行は初回と同じ取得処理を呼ぶ', async () => {
    listDistributions.mockRejectedValueOnce(new Error('network error'))
    const wrapper = await mountSuspended(DistributionsPage)
    await flushMicrotasks()
    expect(wrapper.find('[data-testid="committee-distributions-error-state"]').exists()).toBe(true)

    listDistributions.mockResolvedValueOnce({ data: [] })
    const callsBefore = listDistributions.mock.calls.length
    await wrapper.find('[data-testid="committee-distributions-error-state-retry"]').trigger('click')
    await flushMicrotasks()

    expect(listDistributions.mock.calls.length).toBe(callsBefore + 1)
    expect(wrapper.find('[data-testid="committee-distributions-error-state"]').exists()).toBe(false)
  })
})

async function flushMicrotasks() {
  for (let i = 0; i < 5; i++) {
    await Promise.resolve()
  }
}
