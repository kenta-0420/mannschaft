import { describe, it, expect, beforeAll, beforeEach, vi } from 'vitest'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import { ref } from 'vue'
import CommitteesPage from '~/pages/organizations/[slug]/committees.vue'

/**
 * CMP-260922-2045 第2陣 G3 の根治テスト。
 *
 * `pages/organizations/[slug]/committees.vue` は委員会一覧の取得が失敗しても
 * `committees` を空配列にリセットするだけで、空状態（「委員会がありません」）へ
 * そのまま落ちていた。権限エラー・通信断が「委員会なし」に誤読される欠陥を、
 * エラー専用状態（DashboardErrorState / committees-list-error-state）で修正した。
 *
 * 検証観点:
 *   CM-001 取得失敗時に committees-list-error-state が描画される
 *   CM-002（対照）取得成功・0件時は通常の空状態文言が出て、エラー状態は出ない
 *   CM-003 再試行は初回と同じ取得処理（listCommittees）を呼ぶ
 */

const listCommittees = vi.fn()

mockNuxtImport('useRoute', () => () => ({ params: { slug: 'org-1' } }))
mockNuxtImport('useCommitteeApi', () => () => ({
  listCommittees,
  createCommittee: vi.fn(),
}))
mockNuxtImport('useNotification', () => () => ({ success: vi.fn(), error: vi.fn(), warn: vi.fn(), info: vi.fn() }))
mockNuxtImport('useErrorHandler', () => () => ({ handleApiError: vi.fn() }))
mockNuxtImport('useRoleAccess', () => () => ({ isAdminOrDeputy: ref(false), loadPermissions: vi.fn(async () => {}) }))

beforeAll(async () => {
  listCommittees.mockResolvedValue({ data: [] })
  const warmup = await mountSuspended(CommitteesPage)
  warmup.unmount()
})

describe('pages/organizations/[slug]/committees.vue — 取得失敗時のエラー状態', () => {
  beforeEach(() => {
    listCommittees.mockReset()
  })

  it('CM-001: 取得失敗時に committees-list-error-state が描画される', async () => {
    listCommittees.mockRejectedValue(new Error('network error'))
    const wrapper = await mountSuspended(CommitteesPage)
    await flushMicrotasks()

    expect(wrapper.find('[data-testid="committees-list-error-state"]').exists()).toBe(true)
  })

  it('CM-002（対照）: 取得成功・0件時はエラー状態を出さない', async () => {
    listCommittees.mockResolvedValue({ data: [] })
    const wrapper = await mountSuspended(CommitteesPage)
    await flushMicrotasks()

    expect(wrapper.find('[data-testid="committees-list-error-state"]').exists()).toBe(false)
  })

  it('CM-003: 再試行は初回と同じ取得処理を呼ぶ', async () => {
    listCommittees.mockRejectedValueOnce(new Error('network error'))
    const wrapper = await mountSuspended(CommitteesPage)
    await flushMicrotasks()
    expect(wrapper.find('[data-testid="committees-list-error-state"]').exists()).toBe(true)

    listCommittees.mockResolvedValueOnce({ data: [] })
    const callsBefore = listCommittees.mock.calls.length
    await wrapper.find('[data-testid="committees-list-error-state-retry"]').trigger('click')
    await flushMicrotasks()

    expect(listCommittees.mock.calls.length).toBe(callsBefore + 1)
    expect(wrapper.find('[data-testid="committees-list-error-state"]').exists()).toBe(false)
  })
})

async function flushMicrotasks() {
  for (let i = 0; i < 5; i++) {
    await Promise.resolve()
  }
}
