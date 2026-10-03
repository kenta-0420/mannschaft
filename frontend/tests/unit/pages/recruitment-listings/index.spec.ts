import { describe, it, expect, beforeAll, beforeEach, vi } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import RecruitmentListingsIndexPage from '~/pages/recruitment-listings/index.vue'

/**
 * CMP-260922-2045 の根治テスト。
 *
 * `pages/recruitment-listings/index.vue` は検索失敗を無言で握りつぶし（catch 本体が空）、
 * 「条件に合う募集が見つかりませんでした」という 0 件用の空状態文言をそのまま出していた。
 * 通信断・権限エラーが「該当なし」に誤読される欠陥を、エラー専用状態
 * （DashboardErrorState / recruitment-listings-error-state）で修正した。
 *
 * 検証観点:
 *   RL-001 検索失敗時に recruitment-listings-error-state が描画される
 *   RL-002（対照）検索成功・0件時は通常の空状態文言が出て、エラー状態は出ない
 */

const listCategories = vi.fn()
const searchListings = vi.fn()

vi.mock('~/composables/useRecruitmentApi', () => ({
  useRecruitmentApi: () => ({ listCategories, searchListings }),
}))

beforeAll(async () => {
  listCategories.mockResolvedValue({ data: [] })
  searchListings.mockResolvedValue({ data: [], meta: { total: 0, page: 0, size: 20, totalPages: 0 } })
  const warmup = await mountSuspended(RecruitmentListingsIndexPage)
  warmup.unmount()
})

describe('pages/recruitment-listings/index.vue — 取得失敗時のエラー状態', () => {
  beforeEach(() => {
    listCategories.mockReset()
    searchListings.mockReset()
    listCategories.mockResolvedValue({ data: [] })
  })

  it('RL-001: 検索失敗時に recruitment-listings-error-state が描画される', async () => {
    searchListings.mockRejectedValue(new Error('network error'))
    const wrapper = await mountSuspended(RecruitmentListingsIndexPage)
    await flushMicrotasks()

    expect(wrapper.find('[data-testid="recruitment-listings-error-state"]').exists()).toBe(true)
  })

  it('RL-002（対照）: 検索成功・0件時はエラー状態を出さない', async () => {
    searchListings.mockResolvedValue({ data: [], meta: { total: 0, page: 0, size: 20, totalPages: 0 } })
    const wrapper = await mountSuspended(RecruitmentListingsIndexPage)
    await flushMicrotasks()

    expect(wrapper.find('[data-testid="recruitment-listings-error-state"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('No recruitments found matching your criteria')
  })
})

async function flushMicrotasks() {
  for (let i = 0; i < 5; i++) {
    await Promise.resolve()
  }
}
