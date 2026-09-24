import { describe, it, expect, beforeAll, beforeEach, vi } from 'vitest'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import TeamJobsIndexPage from '~/pages/teams/[slug]/jobs/index.vue'

/**
 * CMP-260922-2045 の根治テスト。
 *
 * `pages/teams/[slug]/jobs/index.vue`（Requester 視点の求人一覧）は取得失敗時に
 * `jobs` を空配列にリセットするだけで、そのまま空状態（「求人がありません」）へ落ちていた。
 * エラー専用状態（DashboardErrorState / team-jobs-list-error-state）で修正した。
 *
 * 検証観点:
 *   TJ-001 取得失敗時に team-jobs-list-error-state が描画される
 *   TJ-002（対照）取得成功・0件時はエラー状態を出さない
 */

const searchJobs = vi.fn()

vi.mock('~/composables/jobs/useJobPostingApi', () => ({
  useJobPostingApi: () => ({ searchJobs }),
}))

const notificationMock = { success: vi.fn(), error: vi.fn(), warn: vi.fn(), info: vi.fn() }
vi.mock('~/composables/useNotification', () => ({ useNotification: () => notificationMock }))

mockNuxtImport('useRoute', () => () => ({ params: { slug: '1' } }))

beforeAll(async () => {
  searchJobs.mockResolvedValue({ data: [], meta: { total: 0, page: 0, size: 20, totalPages: 0 } })
  const warmup = await mountSuspended(TeamJobsIndexPage)
  warmup.unmount()
})

describe('pages/teams/[slug]/jobs/index.vue — 取得失敗時のエラー状態', () => {
  beforeEach(() => {
    searchJobs.mockReset()
    notificationMock.error.mockClear()
  })

  it('TJ-001: 取得失敗時に team-jobs-list-error-state が描画される', async () => {
    searchJobs.mockRejectedValue(new Error('network error'))
    const wrapper = await mountSuspended(TeamJobsIndexPage)
    await flushMicrotasks()

    expect(wrapper.find('[data-testid="team-jobs-list-error-state"]').exists()).toBe(true)
  })

  it('TJ-002（対照）: 取得成功・0件時はエラー状態を出さない', async () => {
    searchJobs.mockResolvedValue({ data: [], meta: { total: 0, page: 0, size: 20, totalPages: 0 } })
    const wrapper = await mountSuspended(TeamJobsIndexPage)
    await flushMicrotasks()

    expect(wrapper.find('[data-testid="team-jobs-list-error-state"]').exists()).toBe(false)
  })
})

async function flushMicrotasks() {
  for (let i = 0; i < 5; i++) {
    await Promise.resolve()
  }
}
