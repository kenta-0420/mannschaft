import { describe, it, expect, beforeAll, beforeEach, vi } from 'vitest'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import { ref } from 'vue'
import CommitteeDetailPage from '~/pages/committees/[id]/index.vue'

/**
 * CMP-260922-2045 第2陣 G3 の根治テスト。
 *
 * `pages/committees/[id]/index.vue` は委員会詳細取得が失敗すると `committee` が
 * null のまま `v-else-if="committee"` が false になり、何も描画しない空白画面に
 * 落ちていた（権限エラー・通信断が「存在しない」と見分けがつかない）。
 * エラー専用状態（DashboardErrorState / committee-detail-error-state）で修正した。
 *
 * 検証観点:
 *   CDT-001 取得失敗時に committee-detail-error-state が描画される
 *   CDT-002（対照）取得成功時は委員会名が表示され、エラー状態は出ない
 *   CDT-003 再試行は初回と同じ取得処理（loadData: getCommittee + listMembers）を呼ぶ
 */

const getCommittee = vi.fn()
const listMembers = vi.fn()
const listInvitations = vi.fn()

mockNuxtImport('useRoute', () => () => ({ params: { id: '1' } }))
mockNuxtImport('useCommitteeApi', () => () => ({
  getCommittee,
  listMembers,
  transitionStatus: vi.fn(),
  removeMember: vi.fn(),
  leaveCommittee: vi.fn(),
}))
mockNuxtImport('useCommitteeInvitationApi', () => () => ({
  listInvitations,
  sendInvitations: vi.fn(),
  cancelInvitation: vi.fn(),
}))
mockNuxtImport('useNotification', () => () => ({ success: vi.fn(), error: vi.fn(), warn: vi.fn(), info: vi.fn() }))
mockNuxtImport('useErrorHandler', () => () => ({ handleApiError: vi.fn() }))
mockNuxtImport('useDatetime', () => () => ({ userTimezone: ref('Asia/Tokyo') }))

const committeeData = {
  id: 1,
  name: 'テスト委員会',
  description: null,
  status: 'ACTIVE',
  purposeTag: null,
  startDate: null,
  endDate: null,
  myRole: 'MEMBER',
}

beforeAll(async () => {
  getCommittee.mockResolvedValue({ data: committeeData })
  listMembers.mockResolvedValue({ data: [] })
  listInvitations.mockResolvedValue({ data: [] })
  const warmup = await mountSuspended(CommitteeDetailPage)
  warmup.unmount()
})

describe('pages/committees/[id]/index.vue — 取得失敗時のエラー状態', () => {
  beforeEach(() => {
    getCommittee.mockReset()
    listMembers.mockReset()
  })

  it('CDT-001: 取得失敗時に committee-detail-error-state が描画される', async () => {
    getCommittee.mockRejectedValue(new Error('network error'))
    listMembers.mockResolvedValue({ data: [] })
    const wrapper = await mountSuspended(CommitteeDetailPage)
    await flushMicrotasks()

    expect(wrapper.find('[data-testid="committee-detail-error-state"]').exists()).toBe(true)
  })

  it('CDT-002（対照）: 取得成功時は委員会名が表示されエラー状態は出ない', async () => {
    getCommittee.mockResolvedValue({ data: committeeData })
    listMembers.mockResolvedValue({ data: [] })
    const wrapper = await mountSuspended(CommitteeDetailPage)
    await flushMicrotasks()

    expect(wrapper.find('[data-testid="committee-detail-error-state"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('テスト委員会')
  })

  it('CDT-003: 再試行は初回と同じ取得処理（loadData）を呼ぶ', async () => {
    getCommittee.mockRejectedValueOnce(new Error('network error'))
    listMembers.mockResolvedValue({ data: [] })
    const wrapper = await mountSuspended(CommitteeDetailPage)
    await flushMicrotasks()
    expect(wrapper.find('[data-testid="committee-detail-error-state"]').exists()).toBe(true)

    getCommittee.mockResolvedValueOnce({ data: committeeData })
    const callsBefore = listMembers.mock.calls.length
    await wrapper.find('[data-testid="committee-detail-error-state-retry"]').trigger('click')
    await flushMicrotasks()

    expect(listMembers.mock.calls.length).toBe(callsBefore + 1)
    expect(wrapper.find('[data-testid="committee-detail-error-state"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('テスト委員会')
  })
})

async function flushMicrotasks() {
  for (let i = 0; i < 5; i++) {
    await Promise.resolve()
  }
}
