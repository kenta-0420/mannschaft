import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { nextTick } from 'vue'
import { useAuthStore } from '~/stores/useAuthStore'
import ChangeRequestsPage from '~/pages/teams/[slug]/shifts/[scheduleId]/change-requests.vue'
import type { ChangeRequest, ShiftScheduleResponse } from '~/types/shift'

/**
 * CMP-261010-1432 FE-a: 変更依頼画面（teams/[slug]/shifts/[scheduleId]/change-requests）の AC。
 *   AC-A1 チーム ADMIN / DEPUTY_ADMIN は OPEN の他人の依頼に承認・却下ボタンを見る
 *   AC-A2 一般メンバー・SUPPORTER・権限取得失敗では出ない
 *   AC-A3 却下コメントが review API に渡る
 *   AC-E1 403 で DashboardErrorState(forbidden)。フォーム・一覧を描画しない
 *   AC-E2 404 は notFoundOrForbidden 表示で再試行ボタンを出さない
 *   AC-E3 5xx は再試行ボタンが出て、押すと同じ取得をやり直す
 *   AC-E4 URL の slug のチームと schedule.teamId が不一致なら notFound 扱い
 *   AC-J3b PUBLISHED 以降は作成フォームを出さず、閲覧・取り下げはできる
 *   AC-J5  SUPPORTER が直打ちしても空フォームを出さない（取得 403 → 拒否表示）
 */
const mocks = await vi.hoisted(async () => {
  const { ref } = await import('vue')
  return {
    getSchedule: vi.fn(),
    listChangeRequests: vi.fn(),
    createChangeRequest: vi.fn(),
    reviewChangeRequest: vi.fn(),
    withdrawChangeRequest: vi.fn(),
    getTeam: vi.fn(),
    loadPermissions: vi.fn(),
    isAdminOrDeputy: ref(false),
    handleApiError: vi.fn(),
  }
})

mockNuxtImport('useRoute', () => () => ({ params: { slug: 'team-a', scheduleId: '10' } }))
mockNuxtImport('useShiftApi', () => () => ({
  getSchedule: mocks.getSchedule,
  listChangeRequests: mocks.listChangeRequests,
  createChangeRequest: mocks.createChangeRequest,
  reviewChangeRequest: mocks.reviewChangeRequest,
  withdrawChangeRequest: mocks.withdrawChangeRequest,
}))
mockNuxtImport('useTeamApi', () => () => ({ getTeam: mocks.getTeam }))
mockNuxtImport('useRoleAccess', () => () => ({
  isAdminOrDeputy: mocks.isAdminOrDeputy,
  loadPermissions: mocks.loadPermissions,
}))
mockNuxtImport('useNotification', () => () => ({ success: vi.fn(), error: vi.fn() }))
mockNuxtImport('useErrorHandler', () => () => ({ handleApiError: mocks.handleApiError }))

function schedule(status: ShiftScheduleResponse['status']['status'] = 'COLLECTING', teamId = 12): ShiftScheduleResponse {
  return {
    id: 10,
    teamId,
    content: { title: '10月第1週', periodType: 'WEEKLY', note: null },
    period: { startDate: '2026-10-01', endDate: '2026-10-07', requestDeadline: null },
    status: { status, publishedAt: null, publishedBy: null },
    audit: { createdBy: 1, createdAt: '', updatedAt: '' },
  }
}

function request(id: number, requestedBy: number): ChangeRequest {
  return {
    id,
    scheduleId: 10,
    slotId: null,
    requestInfo: { requestType: 'PRE_CONFIRM_EDIT', reason: '理由', requestedBy },
    reviewInfo: { status: 'OPEN', reviewerId: null, reviewComment: null, reviewedAt: null },
    timing: { expiresAt: null, createdAt: '2026-10-01T00:00:00' },
    requesterName: requestedBy === 5 ? '自分' : '山田花子',
  }
}

async function mountPage() {
  const wrapper = await mountSuspended(ChangeRequestsPage)
  // 実ストアを使う（プラグインが他のメソッドも呼ぶため差し替えない）。ログイン中の利用者は id=5。
  useAuthStore().$patch({ user: { id: 5, systemRole: 'USER' } } as never)
  await flushPromises()
  return wrapper
}

beforeEach(() => {
  document.body.innerHTML = ''
  Object.values(mocks).forEach((m) => {
    if (typeof m === 'function' && 'mockReset' in m) m.mockReset()
  })
  mocks.isAdminOrDeputy.value = false
  mocks.getSchedule.mockResolvedValue(schedule())
  mocks.getTeam.mockResolvedValue({ data: { numericId: 12 } })
  mocks.listChangeRequests.mockResolvedValue([request(1, 7), request(2, 5)])
  mocks.loadPermissions.mockResolvedValue({ ok: true })
})

describe('change-requests.vue 審査ボタンの判定（AC-A1 / AC-A2）', () => {
  it('AC-A1: チーム ADMIN / DEPUTY_ADMIN（useRoleAccess.isAdminOrDeputy）は他人の依頼に承認・却下を見る', async () => {
    mocks.isAdminOrDeputy.value = true
    const wrapper = await mountPage()
    expect(wrapper.find('[data-testid="change-request-approve-1"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="change-request-reject-1"]').exists()).toBe(true)
    // AC-B4: 自分の依頼には出ない
    expect(wrapper.find('[data-testid="change-request-approve-2"]').exists()).toBe(false)
  })

  it('AC-A2: 一般メンバー・SUPPORTER・権限取得失敗（isAdminOrDeputy=false）では承認・却下が出ない', async () => {
    mocks.isAdminOrDeputy.value = false
    mocks.loadPermissions.mockResolvedValue({ ok: false, error: { statusCode: 500 } })
    const wrapper = await mountPage()
    expect(wrapper.find('[data-testid="change-request-approve-1"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="change-request-reject-1"]').exists()).toBe(false)
  })

  it('AC-A3: 却下ダイアログの審査コメントが reviewChangeRequest に渡る', async () => {
    mocks.isAdminOrDeputy.value = true
    mocks.reviewChangeRequest.mockResolvedValue({ ...request(1, 7), reviewInfo: { status: 'REJECTED', reviewerId: 5, reviewComment: 'x', reviewedAt: null } })
    const wrapper = await mountPage()

    await wrapper.find('[data-testid="change-request-reject-1"]').trigger('click')
    await nextTick()
    const textarea = document.body.querySelector<HTMLTextAreaElement>('[data-testid="change-request-review-comment"]')!
    textarea.value = '人手が足りません'
    textarea.dispatchEvent(new Event('input'))
    await nextTick()
    document.body.querySelector<HTMLButtonElement>('[data-testid="change-request-review-confirm"]')!.click()
    await flushPromises()

    expect(mocks.reviewChangeRequest).toHaveBeenCalledTimes(1)
    expect(mocks.reviewChangeRequest).toHaveBeenCalledWith(
      1,
      expect.objectContaining({ decision: 'REJECTED', reviewComment: '人手が足りません' }),
    )
  })
})

describe('change-requests.vue 取得失敗（AC-E1〜E4 / AC-J5）', () => {
  it('AC-E1: 一覧取得が 403 なら forbidden を描画し、フォーム・一覧は描画しない', async () => {
    mocks.listChangeRequests.mockRejectedValue({ statusCode: 403 })
    const wrapper = await mountPage()
    const state = wrapper.find('[data-testid="change-request-load-error"]')
    expect(state.exists()).toBe(true)
    expect(state.text()).toContain("This page isn't available for this account")
    expect(wrapper.find('[data-testid="change-request-form"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="change-request-list"]').exists()).toBe(false)
  })

  it('AC-J5: SUPPORTER が直打ちしてシフト表取得が 403 でも、空フォームでなく拒否表示になる', async () => {
    mocks.getSchedule.mockRejectedValue({ statusCode: 403 })
    const wrapper = await mountPage()
    expect(wrapper.find('[data-testid="change-request-load-error"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="change-request-form"]').exists()).toBe(false)
  })

  it('AC-E2: 404 は再試行ボタンを出さない', async () => {
    mocks.getSchedule.mockRejectedValue({ statusCode: 404 })
    const wrapper = await mountPage()
    expect(wrapper.find('[data-testid="change-request-load-error"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="change-request-load-error-retry"]').exists()).toBe(false)
  })

  it('AC-E3: 5xx は再試行ボタンが出て、押すと取得をやり直して一覧が出る', async () => {
    mocks.listChangeRequests.mockRejectedValueOnce({ statusCode: 500 })
    const wrapper = await mountPage()
    expect(wrapper.find('[data-testid="change-request-load-error"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="change-request-list"]').exists()).toBe(false)

    await wrapper.find('[data-testid="change-request-load-error-retry"]').trigger('click')
    await flushPromises()

    expect(mocks.listChangeRequests).toHaveBeenCalledTimes(2)
    expect(wrapper.find('[data-testid="change-request-load-error"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="change-request-list"]').exists()).toBe(true)
  })

  it('AC-E4: URL の slug のチーム ID と schedule.teamId が不一致なら notFound 扱い（再試行なし・フォーム無し）', async () => {
    mocks.getSchedule.mockResolvedValue(schedule('COLLECTING', 99))
    const wrapper = await mountPage()
    const state = wrapper.find('[data-testid="change-request-load-error"]')
    expect(state.exists()).toBe(true)
    expect(wrapper.find('[data-testid="change-request-load-error-retry"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="change-request-form"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="change-request-list"]').exists()).toBe(false)
  })
})

describe('change-requests.vue 受付状態（AC-J3b）', () => {
  it('AC-J3b: COLLECTING までは作成フォームを出す', async () => {
    const wrapper = await mountPage()
    expect(wrapper.find('[data-testid="change-request-form"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="change-request-closed-notice"]').exists()).toBe(false)
  })

  it.each(['PUBLISHED', 'ARCHIVED'] as const)(
    'AC-J3b: %s では作成フォームを出さず案内を出し、既存依頼の閲覧と取り下げはできる',
    async (status) => {
      mocks.getSchedule.mockResolvedValue(schedule(status))
      const wrapper = await mountPage()
      expect(wrapper.find('[data-testid="change-request-form"]').exists()).toBe(false)
      expect(wrapper.find('[data-testid="change-request-closed-notice"]').exists()).toBe(true)
      expect(wrapper.find('[data-testid="change-request-list"]').exists()).toBe(true)
      expect(wrapper.find('[data-testid="change-request-withdraw-2"]').exists()).toBe(true)
    },
  )
})
