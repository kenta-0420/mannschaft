import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import MemberProfilesPage from '~/pages/organizations/[slug]/member-profiles.vue'
import type { MemberProfile, TeamPage } from '~/types/member-profile'

/**
 * CMP-261002-1342 の根治テスト。
 *
 * `member-profiles.vue` の `handleDeleteMember` は確認ダイアログを出さず、
 * ただちに `memberProfileApi.deleteMember` を呼んでいた（物理削除・取り消し不可）。
 * ページ削除（`confirmDeletePage` / `showDeletePageDialog`）と同じ確認ダイアログ方式に揃えた。
 *
 * 検証観点:
 *   MP-001 削除ボタンを押すと確認ダイアログが表示される（deleteMember は呼ばれない）
 *   MP-002 キャンセルでは deleteMember が呼ばれない
 *   MP-003 確定すると deleteMember が1回だけ呼ばれる
 */

const notificationSuccessMock = vi.fn()
const notificationErrorMock = vi.fn()

const deleteMember = vi.fn(async () => {})
const listMembers = vi.fn(async () => ({
  data: [] as MemberProfile[],
  meta: { total: 0, page: 0, size: 100, totalPages: 0 },
}))
const listPages = vi.fn(async () => ({
  data: [] as TeamPage[],
  meta: { total: 0, page: 0, size: 100, totalPages: 0 },
}))

const memberProfileApiMock = {
  listPages,
  createPage: vi.fn(),
  getPage: vi.fn(),
  updatePage: vi.fn(),
  deletePage: vi.fn(),
  changePageStatus: vi.fn(),
  issuePreviewToken: vi.fn(),
  revokePreviewToken: vi.fn(),
  listMembers,
  getMember: vi.fn(),
  createMember: vi.fn(),
  updateMember: vi.fn(),
  deleteMember,
  bulkCreateMembers: vi.fn(),
  copyMembers: vi.fn(),
  reorderMembers: vi.fn(),
  lookupMembers: vi.fn(),
  listFields: vi.fn(),
  createField: vi.fn(),
  updateField: vi.fn(),
  deactivateField: vi.fn(),
}

vi.mock('~/composables/useMemberProfileApi', () => ({
  useMemberProfileApi: () => memberProfileApiMock,
}))

vi.mock('~/composables/useOrganizationApi', () => ({
  useOrganizationApi: () => ({
    getOrganization: vi.fn(async () => ({ data: { numericId: 1 } })),
  }),
}))

vi.mock('~/composables/useRoleAccess', () => ({
  useRoleAccess: () => ({
    loadPermissions: vi.fn(async () => undefined),
    isAdmin: { value: true },
  }),
}))

vi.mock('~/composables/useNotification', () => ({
  useNotification: () => ({
    success: notificationSuccessMock,
    error: notificationErrorMock,
  }),
}))

mockNuxtImport('useRoute', () => () => ({ params: { slug: 'org-1' } }))

const MEMBER: MemberProfile = {
  id: 10,
  teamPageId: 1,
  displayName: '山田太郎',
  memberNumber: '7',
  bio: null,
  position: null,
  userId: null,
  customFieldValues: null,
  sortOrder: 0,
} as unknown as MemberProfile

const PAGE: TeamPage = {
  id: 1,
  title: '2026年度メンバー',
  slug: 'members-2026',
  pageType: 'YEARLY',
  year: 2026,
  visibility: 'MEMBERS_ONLY',
  status: 'PUBLISHED',
} as unknown as TeamPage

interface MemberProfilesVM {
  handleDeleteMember: (id: number) => void
  executeDeleteMember: () => Promise<void>
  showDeleteMemberDialog: boolean
  deleteTargetMember: MemberProfile | null
}

async function flushMicrotasks() {
  for (let i = 0; i < 5; i++) {
    await Promise.resolve()
  }
}

describe('pages/organizations/[slug]/member-profiles.vue — メンバー削除確認', () => {
  beforeEach(() => {
    notificationSuccessMock.mockReset()
    notificationErrorMock.mockReset()
    deleteMember.mockReset()
    deleteMember.mockResolvedValue(undefined)
    listPages.mockReset()
    listPages.mockResolvedValue({ data: [PAGE], meta: { total: 1, page: 0, size: 100, totalPages: 1 } })
    listMembers.mockReset()
    listMembers.mockResolvedValue({ data: [MEMBER], meta: { total: 1, page: 0, size: 100, totalPages: 1 } })
  })

  async function mountOnMembersView() {
    const wrapper = await mountSuspended(MemberProfilesPage)
    await flushMicrotasks()
    const vm = wrapper.vm as unknown as { view: string; openPage: (p: TeamPage) => Promise<void> }
    await vm.openPage(PAGE)
    await flushMicrotasks()
    return wrapper
  }

  it('MP-001: 削除を押すと確認ダイアログが表示され、deleteMember はまだ呼ばれない', async () => {
    const wrapper = await mountOnMembersView()
    const vm = wrapper.vm as unknown as MemberProfilesVM

    vm.handleDeleteMember(MEMBER.id)
    await wrapper.vm.$nextTick()

    expect(vm.showDeleteMemberDialog).toBe(true)
    expect(vm.deleteTargetMember?.id).toBe(MEMBER.id)
    expect(deleteMember).not.toHaveBeenCalled()
  })

  it('MP-002: キャンセルでは deleteMember が呼ばれない', async () => {
    const wrapper = await mountOnMembersView()
    const vm = wrapper.vm as unknown as MemberProfilesVM

    vm.handleDeleteMember(MEMBER.id)
    await wrapper.vm.$nextTick()

    vm.showDeleteMemberDialog = false
    await wrapper.vm.$nextTick()

    expect(deleteMember).not.toHaveBeenCalled()
  })

  it('MP-003: 確定すると deleteMember が1回だけ呼ばれる', async () => {
    const wrapper = await mountOnMembersView()
    const vm = wrapper.vm as unknown as MemberProfilesVM

    vm.handleDeleteMember(MEMBER.id)
    await wrapper.vm.$nextTick()

    await vm.executeDeleteMember()
    await flushMicrotasks()

    expect(deleteMember).toHaveBeenCalledTimes(1)
    expect(deleteMember).toHaveBeenCalledWith(MEMBER.id)
    expect(vm.showDeleteMemberDialog).toBe(false)
    expect(notificationSuccessMock).toHaveBeenCalled()
  })
})
