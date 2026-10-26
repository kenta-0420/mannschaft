import { describe, it, expect, beforeAll, beforeEach, vi } from 'vitest'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import JoinRequestPage from '~/pages/villages/[id]/join-request.vue'

/**
 * CMP-260922-2045 第2陣 G2 の根治テスト。
 *
 * `pages/villages/[id]/join-request.vue` は審査一覧（村長/長老向け）の取得失敗時に
 * `requests` を空配列にリセットするだけで、PrimeVue DataTable の #empty スロット
 * （「申請はありません」）をそのまま描画していた。エラー専用状態
 * （DashboardErrorState / join-request-review-error-state）で修正した。
 * 申請者本人向けの自分の申請取得（loadMyRequest）は「空状態」ではなく申請フォームへ
 * 落ちる別種の挙動であり、本戦役の対象外（別課題として報告）。
 *
 * 検証観点:
 *   JR-001 審査一覧の取得失敗時に join-request-review-error-state が描画される
 *   JR-002（対照）取得成功・0件時はエラー状態を出さない
 *   JR-003 再試行が初回表示と同じ取得関数（loadRequests）を呼ぶ
 */

const listJoinRequests = vi.fn()
const listMyJoinRequests = vi.fn()
const createJoinRequest = vi.fn()
const approveJoinRequest = vi.fn()
const rejectJoinRequest = vi.fn()
const withdrawJoinRequest = vi.fn()

mockNuxtImport('useVillageMembershipApi', () => () => ({
  createJoinRequest,
  listMyJoinRequests,
  listJoinRequests,
  approveJoinRequest,
  rejectJoinRequest,
  withdrawJoinRequest,
}))

vi.mock('~/composables/useVillageContext', () => ({
  useVillageContext: () => ({
    village: { value: { id: 'v1', name: 'テスト村', joinPolicy: 'APPROVAL' } },
    perms: { value: { isMember: false, isAdmin: true, isHeadman: true, myRole: 'HEADMAN' } },
    currentUserId: { value: 1 },
    refresh: vi.fn(async () => {}),
    myMembership: { value: null },
    openEditDialog: vi.fn(),
  }),
}))

const notificationMock = { success: vi.fn(), error: vi.fn(), warn: vi.fn(), info: vi.fn(), showSuccess: vi.fn(), showError: vi.fn(), showWarn: vi.fn(), showInfo: vi.fn() }
vi.mock('~/composables/useNotification', () => ({ useNotification: () => notificationMock }))

mockNuxtImport('useRoute', () => () => ({ params: { id: 'v1' } }))

beforeAll(async () => {
  listJoinRequests.mockResolvedValue({ content: [], totalElements: 0 })
  listMyJoinRequests.mockResolvedValue([])
  const warmup = await mountSuspended(JoinRequestPage)
  warmup.unmount()
})

describe('pages/villages/[id]/join-request.vue — 審査一覧の取得失敗時のエラー状態', () => {
  beforeEach(() => {
    listJoinRequests.mockReset()
    listMyJoinRequests.mockReset()
    listMyJoinRequests.mockResolvedValue([])
    notificationMock.showError.mockClear()
  })

  it('JR-001: 取得失敗時に join-request-review-error-state が描画される', async () => {
    listJoinRequests.mockRejectedValue(new Error('network error'))
    const wrapper = await mountSuspended(JoinRequestPage)
    await flushMicrotasks()

    expect(wrapper.find('[data-testid="join-request-review-error-state"]').exists()).toBe(true)
  })

  it('JR-002（対照）: 取得成功・0件時はエラー状態を出さない', async () => {
    listJoinRequests.mockResolvedValue({ content: [], totalElements: 0 })
    const wrapper = await mountSuspended(JoinRequestPage)
    await flushMicrotasks()

    expect(wrapper.find('[data-testid="join-request-review-error-state"]').exists()).toBe(false)
  })

  it('JR-003: 再試行が初回表示と同じ取得関数を呼ぶ（成功に回復できる）', async () => {
    listJoinRequests.mockRejectedValueOnce(new Error('network error'))
    listJoinRequests.mockResolvedValueOnce({
      content: [{ id: 'jr1', subjectId: 2, status: 'PENDING', createdAt: '2026-01-01T00:00:00Z' }],
      totalElements: 1,
    })
    const wrapper = await mountSuspended(JoinRequestPage)
    await flushMicrotasks()
    expect(wrapper.find('[data-testid="join-request-review-error-state"]').exists()).toBe(true)

    await wrapper.find('[data-testid="join-request-review-error-state-retry"]').trigger('click')
    await flushMicrotasks()

    expect(listJoinRequests).toHaveBeenCalledTimes(2)
    expect(wrapper.find('[data-testid="join-request-review-error-state"]').exists()).toBe(false)
  })
})

async function flushMicrotasks() {
  for (let i = 0; i < 5; i++) {
    await Promise.resolve()
  }
}
