import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import { setActivePinia, createPinia } from 'pinia'
import { useAuthStore } from '~/stores/useAuthStore'
import TransferOwnershipPanel from '~/components/TransferOwnershipPanel.vue'
import type { MemberResponse } from '~/types/member'

/**
 * CMP-051 TransferOwnershipPanel.vue ユニットテスト。
 *
 * テスト観点:
 *  - TOP-001: 確認入力が未一致のあいだは transferOwnership が呼ばれない（誤爆防止）
 *  - TOP-002: 譲渡先未選択でも transferOwnership が呼ばれない
 *  - TOP-003: 確認完了後、チームは useTeamApi.transferOwnership(slug, userId) で呼ばれる
 *  - TOP-004: 組織は useOrganizationApi.transferOwnership(slug, userId) で呼ばれる
 *  - TOP-005: API エラー（ROLE_001 等）は握りつぶさず handleApiError へ渡す＆ダイアログを閉じない
 *  - TOP-006: 成功時は成功トースト + transferred emit
 *  - TOP-007: 譲渡先候補から自分自身が除外される
 */

const mockTeamTransfer = vi.fn()
const mockTeamGetMembers = vi.fn()
const mockOrgTransfer = vi.fn()
const mockOrgGetMembers = vi.fn()

vi.mock('~/composables/useTeamApi', () => ({
  useTeamApi: () => ({
    transferOwnership: mockTeamTransfer,
    getMembers: mockTeamGetMembers,
  }),
}))

vi.mock('~/composables/useOrganizationApi', () => ({
  useOrganizationApi: () => ({
    transferOwnership: mockOrgTransfer,
    getMembers: mockOrgGetMembers,
  }),
}))

const mockToastSuccess = vi.fn()
const mockToastError = vi.fn()
vi.mock('~/composables/useNotification', () => ({
  useNotification: () => ({
    success: mockToastSuccess,
    info: vi.fn(),
    warn: vi.fn(),
    error: mockToastError,
  }),
}))

const mockHandleApiError = vi.fn()
vi.mock('~/composables/useErrorHandler', () => ({
  useErrorHandler: () => ({
    handleApiError: mockHandleApiError,
    handleError: mockHandleApiError,
    getFieldErrors: () => ({}),
    resolveMessage: (code: string) => code,
  }),
}))

/** 自分（ADMIN 本人）の userId。 */
const MY_USER_ID = 100

function buildMember(overrides: Partial<MemberResponse> = {}): MemberResponse {
  return {
    userId: 200,
    displayName: 'Bob',
    avatarUrl: null,
    roleName: 'MEMBER',
    joinedAt: '2026-01-01T00:00:00',
    ...overrides,
  }
}

function pagedMembers(members: MemberResponse[]) {
  return {
    data: members,
    meta: { page: 0, size: 200, totalElements: members.length, totalPages: 1 },
  }
}

/** コンポーネント内部状態への型付きアクセス（script setup の expose 経由）。 */
interface PanelVm {
  dialogVisible: boolean
  candidates: MemberResponse[]
  targetUserId: number | null
  confirmationName: string
  confirmationMatched: boolean
  canSubmit: boolean
  openDialog: () => Promise<void>
  submit: () => Promise<void>
}

async function mountPanel(scopeType: 'team' | 'organization', scopeName = 'My Team') {
  const wrapper = await mountSuspended(TransferOwnershipPanel, {
    props: { scopeType, scopeSlug: 'my-slug', scopeName },
  })
  // @pinia/nuxt はマウント時に Pinia を張り替えるため、user のセットはマウント後に行う。
  useAuthStore().user = {
    id: MY_USER_ID,
    email: 'owner@example.com',
    fullName: 'Owner',
    profileImageUrl: null,
    timezone: 'Asia/Tokyo',
  }
  return { wrapper, vm: wrapper.vm as unknown as PanelVm }
}

beforeEach(() => {
  setActivePinia(createPinia())
  mockTeamTransfer.mockReset()
  mockOrgTransfer.mockReset()
  mockToastSuccess.mockReset()
  mockToastError.mockReset()
  mockHandleApiError.mockReset()
  mockTeamGetMembers.mockReset()
  mockOrgGetMembers.mockReset()
  mockTeamGetMembers.mockResolvedValue(
    pagedMembers([
      buildMember({ userId: MY_USER_ID, displayName: 'Owner', roleName: 'ADMIN' }),
      buildMember({ userId: 200, displayName: 'Bob' }),
    ]),
  )
  mockOrgGetMembers.mockResolvedValue(
    pagedMembers([
      buildMember({ userId: MY_USER_ID, displayName: 'Owner', roleName: 'ADMIN' }),
      buildMember({ userId: 300, displayName: 'Carol' }),
    ]),
  )
})

afterEach(() => {
  document.body
    .querySelectorAll('[data-testid="transfer-ownership-dialog"]')
    .forEach((el) => el.parentElement?.removeChild(el))
})

describe('TransferOwnershipPanel.vue', () => {
  it('TOP-001: 確認入力が未一致のあいだは transferOwnership が呼ばれない', async () => {
    const { wrapper, vm } = await mountPanel('team')
    await vm.openDialog()
    vm.targetUserId = 200
    vm.confirmationName = 'My Tea' // 1 文字足りない
    await wrapper.vm.$nextTick()

    expect(vm.confirmationMatched).toBe(false)
    expect(vm.canSubmit).toBe(false)

    await vm.submit()

    expect(mockTeamTransfer).not.toHaveBeenCalled()
    expect(mockToastSuccess).not.toHaveBeenCalled()
  })

  it('TOP-002: 譲渡先が未選択なら確認入力が一致していても呼ばれない', async () => {
    const { wrapper, vm } = await mountPanel('team')
    await vm.openDialog()
    vm.confirmationName = 'My Team'
    await wrapper.vm.$nextTick()

    expect(vm.confirmationMatched).toBe(true)
    expect(vm.canSubmit).toBe(false)

    await vm.submit()

    expect(mockTeamTransfer).not.toHaveBeenCalled()
  })

  it('TOP-003: 確認完了後、チームは (slug, userId) で transferOwnership が呼ばれる', async () => {
    mockTeamTransfer.mockResolvedValueOnce(undefined)
    const { wrapper, vm } = await mountPanel('team')
    await vm.openDialog()
    vm.targetUserId = 200
    vm.confirmationName = 'My Team'
    await wrapper.vm.$nextTick()

    expect(vm.canSubmit).toBe(true)
    await vm.submit()

    expect(mockTeamTransfer).toHaveBeenCalledTimes(1)
    expect(mockTeamTransfer).toHaveBeenCalledWith('my-slug', 200)
    expect(mockOrgTransfer).not.toHaveBeenCalled()
  })

  it('TOP-004: 組織は useOrganizationApi.transferOwnership が呼ばれる', async () => {
    mockOrgTransfer.mockResolvedValueOnce(undefined)
    const { wrapper, vm } = await mountPanel('organization', 'My Org')
    await vm.openDialog()
    vm.targetUserId = 300
    vm.confirmationName = 'My Org'
    await wrapper.vm.$nextTick()

    await vm.submit()

    expect(mockOrgTransfer).toHaveBeenCalledTimes(1)
    expect(mockOrgTransfer).toHaveBeenCalledWith('my-slug', 300)
    expect(mockTeamTransfer).not.toHaveBeenCalled()
  })

  it('TOP-005: API エラーは握りつぶさず handleApiError へ渡し、ダイアログを閉じない', async () => {
    // CMP-050: 譲渡先が凍結ユーザーのとき BE は ROLE_001 を返す。
    const roleError = { data: { error: { code: 'ROLE_001', message: '対象ユーザーは凍結されています' } } }
    mockTeamTransfer.mockRejectedValueOnce(roleError)

    const { wrapper, vm } = await mountPanel('team')
    await vm.openDialog()
    vm.targetUserId = 200
    vm.confirmationName = 'My Team'
    await wrapper.vm.$nextTick()

    await vm.submit()
    await wrapper.vm.$nextTick()

    expect(mockHandleApiError).toHaveBeenCalledTimes(1)
    expect(mockHandleApiError.mock.calls[0]?.[0]).toBe(roleError)
    expect(mockToastSuccess).not.toHaveBeenCalled()
    expect(wrapper.emitted('transferred')).toBeFalsy()
    // 失敗時はダイアログを閉じない（やり直せる）
    expect(vm.dialogVisible).toBe(true)
  })

  it('TOP-006: 成功時は成功トースト + transferred emit + ダイアログを閉じる', async () => {
    mockTeamTransfer.mockResolvedValueOnce(undefined)
    const { wrapper, vm } = await mountPanel('team')
    await vm.openDialog()
    vm.targetUserId = 200
    vm.confirmationName = 'My Team'
    await wrapper.vm.$nextTick()

    await vm.submit()
    await wrapper.vm.$nextTick()

    expect(mockToastSuccess).toHaveBeenCalledTimes(1)
    expect(wrapper.emitted('transferred')).toBeTruthy()
    expect(vm.dialogVisible).toBe(false)
    expect(mockHandleApiError).not.toHaveBeenCalled()
  })

  it('TOP-007: 譲渡先候補から自分自身が除外される', async () => {
    const { wrapper, vm } = await mountPanel('team')
    await vm.openDialog()
    await wrapper.vm.$nextTick()

    expect(vm.candidates.map((m) => m.userId)).toEqual([200])
  })

  it('TOP-008: メンバー一覧の取得失敗も握りつぶさず handleApiError へ渡す', async () => {
    const fetchError = { data: { error: { code: 'COMMON_001', message: 'failed' } } }
    mockTeamGetMembers.mockReset()
    mockTeamGetMembers.mockRejectedValueOnce(fetchError)

    const { wrapper, vm } = await mountPanel('team')
    await vm.openDialog()
    await wrapper.vm.$nextTick()

    expect(mockHandleApiError).toHaveBeenCalledTimes(1)
    expect(vm.candidates).toEqual([])
  })
})
