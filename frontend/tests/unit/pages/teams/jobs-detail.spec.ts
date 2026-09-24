import { describe, it, expect, beforeAll, beforeEach, vi } from 'vitest'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import TeamJobDetailPage from '~/pages/teams/[slug]/jobs/[jobId]/index.vue'

/**
 * CMP-260922-2045 の根治テスト。
 *
 * `pages/teams/[slug]/jobs/[jobId]/index.vue`（Requester 視点の求人詳細）は
 * 求人取得（主データ）の失敗時に `job` を null にリセットするだけで、
 * 「求人が見つかりません」という not-found 用の文言をそのまま出していた
 * （権限エラー・通信断が「存在しない」に誤読される）。
 * エラー専用状態（DashboardErrorState / job-detail-error-state）で修正した。
 *
 * 検証観点:
 *   JD-001 求人取得失敗時に job-detail-error-state が描画される
 *   JD-002（対照）求人取得失敗時は not-found 文言（jobmatching.detail.notFound）を出さない
 */

const getJob = vi.fn()
const listApplicationsByJob = vi.fn()
const listMyContracts = vi.fn()

vi.mock('~/composables/jobs/useJobPostingApi', () => ({
  useJobPostingApi: () => ({
    getJob,
    publishJob: vi.fn(),
    closeJob: vi.fn(),
    cancelJob: vi.fn(),
    deleteJob: vi.fn(),
  }),
}))
vi.mock('~/composables/jobs/useJobApplicationApi', () => ({
  useJobApplicationApi: () => ({
    listApplicationsByJob,
    acceptApplication: vi.fn(),
    rejectApplication: vi.fn(),
  }),
}))
vi.mock('~/composables/jobs/useJobContractApi', () => ({
  useJobContractApi: () => ({ listMyContracts }),
}))

const notificationMock = { success: vi.fn(), error: vi.fn(), warn: vi.fn(), info: vi.fn() }
vi.mock('~/composables/useNotification', () => ({ useNotification: () => notificationMock }))

mockNuxtImport('useRoute', () => () => ({ params: { slug: '1', jobId: '10' } }))

beforeAll(async () => {
  listApplicationsByJob.mockResolvedValue({ data: [], meta: { total: 0, page: 0, size: 20, totalPages: 0 } })
  listMyContracts.mockResolvedValue({ data: [], meta: { total: 0, page: 0, size: 100, totalPages: 0 } })
  getJob.mockResolvedValue({
    data: {
      id: 10,
      title: 'テスト求人',
      status: 'OPEN',
      category: null,
      description: '',
      workLocationType: 'ONSITE',
      workAddress: null,
      workStartAt: '2026-01-01T00:00:00Z',
      workEndAt: '2026-01-01T01:00:00Z',
      rewardType: 'FIXED',
      baseRewardJpy: 1000,
      capacity: 1,
      applicationDeadlineAt: '2026-01-01T00:00:00Z',
      visibilityScope: 'TEAM',
      publishAt: '2026-01-01T00:00:00Z',
    },
  })
  const warmup = await mountSuspended(TeamJobDetailPage)
  warmup.unmount()
})

describe('pages/teams/[slug]/jobs/[jobId]/index.vue — 取得失敗時のエラー状態', () => {
  beforeEach(() => {
    getJob.mockReset()
    notificationMock.error.mockClear()
  })

  it('JD-001: 求人取得失敗時に job-detail-error-state が描画される', async () => {
    getJob.mockRejectedValue(new Error('network error'))
    const wrapper = await mountSuspended(TeamJobDetailPage)
    await flushMicrotasks()

    expect(wrapper.find('[data-testid="job-detail-error-state"]').exists()).toBe(true)
  })

  it('JD-002（対照）: 求人取得失敗時は not-found 文言を出さない', async () => {
    getJob.mockRejectedValue(new Error('network error'))
    const wrapper = await mountSuspended(TeamJobDetailPage)
    await flushMicrotasks()

    expect(wrapper.text()).not.toContain('not found')
  })
})

async function flushMicrotasks() {
  for (let i = 0; i < 5; i++) {
    await Promise.resolve()
  }
}
