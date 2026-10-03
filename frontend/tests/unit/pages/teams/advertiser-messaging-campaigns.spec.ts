import { describe, it, expect, beforeAll, beforeEach, vi } from 'vitest'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import TeamMessagingCampaignsIndexPage from '~/pages/teams/[slug]/advertiser/messaging-campaigns/index.vue'

/**
 * CMP-260922-2045 第2陣 G5 の根治テスト（組織版と対になるチーム版）。
 *
 * `pages/teams/[slug]/advertiser/messaging-campaigns/index.vue` はキャンペーン取得が
 * 失敗しても `items` を空配列にリセットするだけで、DataTable の #empty スロット
 * （DashboardEmptyState「キャンペーンがありません」）にそのまま落ちていた。
 * エラー専用状態（DashboardErrorState / advertiser-messaging-campaigns-error-state）で修正した。
 *
 * 検証観点:
 *   TM-001 取得失敗時に advertiser-messaging-campaigns-error-state が描画される
 *   TM-002（対照）取得成功・0件時はエラー状態を出さない
 *   TM-003 再試行が初回表示と同じ取得関数（load）を呼ぶ
 */

const listCampaigns = vi.fn()

vi.mock('~/composables/useAdMessagingCampaignApi', () => ({
  useAdMessagingCampaignApi: () => ({ listCampaigns }),
}))

mockNuxtImport('useRoute', () => () => ({ params: { slug: 'team-1' } }))

beforeAll(async () => {
  listCampaigns.mockResolvedValue({ data: [], meta: { total: 0, page: 0, size: 20, totalPages: 0 } })
  const warmup = await mountSuspended(TeamMessagingCampaignsIndexPage)
  warmup.unmount()
})

describe('pages/teams/[slug]/advertiser/messaging-campaigns/index.vue — 取得失敗時のエラー状態', () => {
  beforeEach(() => {
    listCampaigns.mockReset()
  })

  it('TM-001: 取得失敗時に advertiser-messaging-campaigns-error-state が描画される', async () => {
    listCampaigns.mockRejectedValue(new Error('network error'))
    const wrapper = await mountSuspended(TeamMessagingCampaignsIndexPage)
    await flushMicrotasks()

    expect(wrapper.find('[data-testid="advertiser-messaging-campaigns-error-state"]').exists()).toBe(true)
  })

  it('TM-002（対照）: 取得成功・0件時はエラー状態を出さない', async () => {
    listCampaigns.mockResolvedValue({ data: [], meta: { total: 0, page: 0, size: 20, totalPages: 0 } })
    const wrapper = await mountSuspended(TeamMessagingCampaignsIndexPage)
    await flushMicrotasks()

    expect(wrapper.find('[data-testid="advertiser-messaging-campaigns-error-state"]').exists()).toBe(false)
  })

  it('TM-003: 再試行が初回表示と同じ取得処理（load）を呼ぶ', async () => {
    listCampaigns.mockRejectedValueOnce(new Error('network error'))
    listCampaigns.mockResolvedValueOnce({ data: [], meta: { total: 0, page: 0, size: 20, totalPages: 0 } })
    const wrapper = await mountSuspended(TeamMessagingCampaignsIndexPage)
    await flushMicrotasks()
    expect(wrapper.find('[data-testid="advertiser-messaging-campaigns-error-state"]').exists()).toBe(true)

    await wrapper.find('[data-testid="advertiser-messaging-campaigns-error-state-retry"]').trigger('click')
    await flushMicrotasks()

    expect(listCampaigns).toHaveBeenCalledTimes(2)
    expect(wrapper.find('[data-testid="advertiser-messaging-campaigns-error-state"]').exists()).toBe(false)
  })
})

async function flushMicrotasks() {
  for (let i = 0; i < 5; i++) {
    await Promise.resolve()
  }
}
