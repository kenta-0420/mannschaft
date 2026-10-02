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
 *   JD-003 初回で求人取得が失敗→再試行で成功したとき、応募者一覧の API
 *          （listApplicationsByJob）も呼ばれ、応募者が表示される（検分差し戻し分。
 *          初回表示 onMounted は「loadJob→成功なら loadApplications」だが、
 *          再試行が loadJob だけを呼ぶと応募者一覧が未取得のまま残っていた）
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

  it('JD-003: 初回失敗→再試行で成功すると応募者一覧も取得され表示される', async () => {
    listApplicationsByJob.mockReset()
    listApplicationsByJob.mockResolvedValue({
      data: [{
        id: 1,
        jobPostingId: 10,
        status: 'APPLIED',
        selfPr: null,
        appliedAt: '2026-01-01T00:00:00Z',
      }],
      meta: { total: 1, page: 0, size: 20, totalPages: 1 },
    })

    // 初回は求人取得に失敗させる
    getJob.mockRejectedValueOnce(new Error('network error'))
    const wrapper = await mountSuspended(TeamJobDetailPage)
    await flushMicrotasks()
    expect(wrapper.find('[data-testid="job-detail-error-state"]').exists()).toBe(true)
    // 初回は求人取得自体が失敗しているので、応募者一覧 API はまだ呼ばれていないはず
    expect(listApplicationsByJob).not.toHaveBeenCalled()

    // 再試行では求人取得を成功させる
    getJob.mockResolvedValueOnce({
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

    const retryButton = wrapper.find('[data-testid="job-detail-error-state-retry"]')
    expect(retryButton.exists()).toBe(true)
    await retryButton.trigger('click')
    await flushMicrotasks()

    // 再試行後は求人取得成功に伴い、応募者一覧 API も呼ばれていること
    expect(listApplicationsByJob).toHaveBeenCalled()
    expect(wrapper.find('[data-testid="job-detail-error-state"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('テスト求人')
  })
})

async function flushMicrotasks() {
  for (let i = 0; i < 5; i++) {
    await Promise.resolve()
  }
}
