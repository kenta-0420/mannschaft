import { describe, it, expect, beforeAll, beforeEach, vi } from 'vitest'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import JobsIndexPage from '~/pages/jobs/index.vue'

/**
 * CMP-260922-2045 の根治テスト。
 *
 * `pages/jobs/index.vue` は求人取得が失敗しても `jobs` を空配列にリセットするだけで、
 * 空状態（「求人がありません」）へそのまま落ちていた。権限エラー・通信断が「求人なし」に
 * 誤読される欠陥を、エラー専用状態（DashboardErrorState / jobs-list-error-state）で修正した。
 *
 * 検証観点:
 *   JI-001 取得失敗時に jobs-list-error-state が描画される
 *   JI-002（対照）取得成功・0件時は通常の空状態文言が出て、エラー状態は出ない
 */

const searchJobs = vi.fn()

vi.mock('~/composables/jobs/useJobPostingApi', () => ({
  useJobPostingApi: () => ({ searchJobs }),
}))

const notificationMock = { success: vi.fn(), error: vi.fn(), warn: vi.fn(), info: vi.fn() }
vi.mock('~/composables/useNotification', () => ({ useNotification: () => notificationMock }))

const teamStoreStub = {
  myTeams: [{ id: 1, slug: 'team-1', name: 'Team 1', nickname1: null, iconUrl: null, role: 'MEMBER', template: 'default', memberCount: 5 }],
  loading: false,
  fetchMyTeams: vi.fn(async () => {}),
}
mockNuxtImport('useTeamStore', () => () => teamStoreStub)

beforeAll(async () => {
  searchJobs.mockResolvedValue({ data: [], meta: { total: 0, page: 0, size: 20, totalPages: 0 } })
  const warmup = await mountSuspended(JobsIndexPage)
  warmup.unmount()
})

describe('pages/jobs/index.vue — 取得失敗時のエラー状態', () => {
  beforeEach(() => {
    searchJobs.mockReset()
    notificationMock.error.mockClear()
  })

  it('JI-001: 取得失敗時に jobs-list-error-state が描画される', async () => {
    searchJobs.mockRejectedValue(new Error('network error'))
    const wrapper = await mountSuspended(JobsIndexPage)
    await flushMicrotasks()

    expect(wrapper.find('[data-testid="jobs-list-error-state"]').exists()).toBe(true)
  })

  it('JI-002（対照）: 取得成功・0件時はエラー状態を出さない', async () => {
    searchJobs.mockResolvedValue({ data: [], meta: { total: 0, page: 0, size: 20, totalPages: 0 } })
    const wrapper = await mountSuspended(JobsIndexPage)
    await flushMicrotasks()

    expect(wrapper.find('[data-testid="jobs-list-error-state"]').exists()).toBe(false)
  })
})

async function flushMicrotasks() {
  for (let i = 0; i < 5; i++) {
    await Promise.resolve()
  }
}
