import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import { ref } from 'vue'
import { flushPromises, type VueWrapper } from '@vue/test-utils'
import MemberProfilesPage from '~/pages/organizations/[slug]/member-profiles.vue'
import type { MemberProfile, TeamPage } from '~/types/member-profile'

/**
 * CMP-261002-1342 の根治テスト。
 *
 * `member-profiles.vue` の `handleDeleteMember` は確認ダイアログを出さず、
 * ただちに `memberProfileApi.deleteMember` を呼んでいた（物理削除・取り消し不可）。
 * ページ削除（`confirmDeletePage` / `showDeletePageDialog`）と同じ確認ダイアログ方式に揃えた。
 *
 * PR #3589 検分指摘の根治:
 *   - 実際の削除ボタン・キャンセル/確定ボタンを `trigger('click')` で操作し、
 *     ボタンの配線（@click の結び先）自体を検証する（VM直接操作では配線ミスを検出できない）
 *   - 削除中の二重操作防止（連打で deleteMember が1回のみ呼ばれること）
 *   - API 失敗時にエラーが通知され、ダイアログ・削除対象が握りつぶされず残ること
 *   - 削除成功後、一覧から対象メンバーが消えること（再取得モックを削除後の内容に変える）
 *   - 削除対象・送信中状態が成功後にリセットされること
 *
 * 検証観点:
 *   MP-001 削除ボタンを押すと確認ダイアログが表示される（deleteMember は呼ばれない）
 *   MP-002 キャンセルボタンを押すと deleteMember が呼ばれない
 *   MP-003 確定ボタンを押すと deleteMember が1回だけ呼ばれる（連打しても1回）
 *   MP-004 API が失敗したときエラーが通知され、ダイアログ・削除対象が残る
 *   MP-005 削除成功後、一覧から対象メンバーが消える
 *   MP-006 削除成功後、削除対象・送信中状態がリセットされる
 */

const notificationSuccessMock = vi.fn()
const notificationErrorMock = vi.fn()
const captureQuietMock = vi.fn()

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

// isAdmin は実物では ComputedRef（readonly な .value を持つ）。
// プレーンオブジェクト { value: true } ではロジック次第で false を渡しても
// truthy のまま通過してしまうため、実物と同じ ref 相当の形にする（検分指摘3）。
vi.mock('~/composables/useRoleAccess', () => ({
  useRoleAccess: () => ({
    loadPermissions: vi.fn(async () => undefined),
    isAdmin: ref(true),
  }),
}))

vi.mock('~/composables/useNotification', () => ({
  useNotification: () => ({
    success: notificationSuccessMock,
    error: notificationErrorMock,
  }),
}))

// useErrorHandler 自身は実物を使う（handleApiError の分岐を検証したいため）。
// 内部の useErrorReport だけ captureQuiet をモックして BE への実 $fetch を止める。
vi.mock('~/composables/useErrorReport', () => ({
  useErrorReport: () => ({
    captureQuiet: captureQuietMock,
  }),
}))

mockNuxtImport('useRoute', () => () => ({ params: { slug: 'org-1' } }))

function makeMember(overrides: Partial<MemberProfile> = {}): MemberProfile {
  return {
    id: 10,
    teamPageId: 1,
    displayName: '山田太郎',
    memberNumber: '7',
    bio: null,
    position: null,
    userId: null,
    customFieldValues: null,
    sortOrder: 0,
    ...overrides,
  } as unknown as MemberProfile
}

const MEMBER = makeMember()

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
  showDeleteMemberDialog: boolean
  deleteTargetMember: MemberProfile | null
  deletingMember: boolean
}

/** 一覧中の削除（ゴミ箱アイコン）ボタンを探す。severity="danger" な唯一のアイコンボタン。 */
function findDeleteTriggerButton(wrapper: VueWrapper) {
  return wrapper
    .findAllComponents({ name: 'Button' })
    .find((b) => b.props('icon') === 'pi pi-trash' && b.props('severity') === 'danger')
}

/** 削除確認ダイアログのフッターボタンをラベルで探す（teleport されてもコンポーネント木には乗る）。 */
function findDialogButton(wrapper: VueWrapper, label: string) {
  return wrapper
    .findAllComponents({ name: 'Button' })
    .find((b) => b.props('label') === label)
}

describe('pages/organizations/[slug]/member-profiles.vue — メンバー削除確認', () => {
  beforeEach(() => {
    notificationSuccessMock.mockReset()
    notificationErrorMock.mockReset()
    captureQuietMock.mockReset()
    deleteMember.mockReset()
    deleteMember.mockResolvedValue(undefined)
    listPages.mockReset()
    listPages.mockResolvedValue({ data: [PAGE], meta: { total: 1, page: 0, size: 100, totalPages: 1 } })
    listMembers.mockReset()
    listMembers.mockResolvedValue({ data: [MEMBER], meta: { total: 1, page: 0, size: 100, totalPages: 1 } })
  })

  async function mountOnMembersView() {
    const wrapper = await mountSuspended(MemberProfilesPage)
    await flushPromises()
    const vm = wrapper.vm as unknown as { openPage: (p: TeamPage) => Promise<void> }
    await vm.openPage(PAGE)
    await flushPromises()
    return wrapper
  }

  it('MP-001: 削除ボタンを押すと確認ダイアログが表示され、deleteMember はまだ呼ばれない', async () => {
    const wrapper = await mountOnMembersView()

    const trashButton = findDeleteTriggerButton(wrapper)
    expect(trashButton).toBeTruthy()
    await trashButton!.trigger('click')
    await flushPromises()

    const vm = wrapper.vm as unknown as MemberProfilesVM
    expect(vm.showDeleteMemberDialog).toBe(true)
    expect(vm.deleteTargetMember?.id).toBe(MEMBER.id)
    expect(deleteMember).not.toHaveBeenCalled()
  })

  it('MP-002: キャンセルボタンを押すと deleteMember が呼ばれない', async () => {
    const wrapper = await mountOnMembersView()

    await findDeleteTriggerButton(wrapper)!.trigger('click')
    await flushPromises()

    const cancelButton = findDialogButton(wrapper, 'Cancel')
    expect(cancelButton).toBeTruthy()
    await cancelButton!.trigger('click')
    await flushPromises()

    expect(deleteMember).not.toHaveBeenCalled()
    const vm = wrapper.vm as unknown as MemberProfilesVM
    expect(vm.showDeleteMemberDialog).toBe(false)
  })

  it('MP-003: 確定ボタンを押すと deleteMember が1回だけ呼ばれる（連打しても1回）', async () => {
    // 解決しない Promise を返し、処理中に連打しても deleteMember が1回しか
    // 呼ばれないことを確かめる（検分指摘1・2: 削除中の二重操作防止）。
    let resolveDelete!: () => void
    deleteMember.mockImplementation(
      () => new Promise<void>((resolve) => { resolveDelete = resolve }),
    )

    const wrapper = await mountOnMembersView()
    await findDeleteTriggerButton(wrapper)!.trigger('click')
    await flushPromises()

    const confirmButton = findDialogButton(wrapper, 'Delete')
    expect(confirmButton).toBeTruthy()

    // 連打（await を挟まない）
    await confirmButton!.trigger('click')
    await confirmButton!.trigger('click')
    await confirmButton!.trigger('click')
    await flushPromises()

    expect(deleteMember).toHaveBeenCalledTimes(1)

    resolveDelete()
    await flushPromises()
    expect(deleteMember).toHaveBeenCalledTimes(1)
  })

  it('MP-004: API が失敗したときエラーが通知され、ダイアログ・削除対象が残る', async () => {
    const apiError = { statusCode: 500, data: {} }
    deleteMember.mockRejectedValue(apiError)

    const wrapper = await mountOnMembersView()
    await findDeleteTriggerButton(wrapper)!.trigger('click')
    await flushPromises()

    const confirmButton = findDialogButton(wrapper, 'Delete')
    await confirmButton!.trigger('click')
    await flushPromises()

    expect(deleteMember).toHaveBeenCalledTimes(1)
    expect(notificationErrorMock).toHaveBeenCalled()
    expect(notificationSuccessMock).not.toHaveBeenCalled()

    const vm = wrapper.vm as unknown as MemberProfilesVM
    // 握りつぶして閉じるのではなく、ダイアログ・削除対象は残り、送信中フラグのみ解除される
    expect(vm.showDeleteMemberDialog).toBe(true)
    expect(vm.deleteTargetMember?.id).toBe(MEMBER.id)
    expect(vm.deletingMember).toBe(false)
  })

  it('MP-005: 削除成功後、一覧から対象メンバーが消える', async () => {
    const other = makeMember({ id: 20, displayName: '鈴木花子' })
    listMembers.mockReset()
    listMembers
      .mockResolvedValueOnce({ data: [MEMBER, other], meta: { total: 2, page: 0, size: 100, totalPages: 1 } })
      .mockResolvedValueOnce({ data: [other], meta: { total: 1, page: 0, size: 100, totalPages: 1 } })

    const wrapper = await mountOnMembersView()
    expect(wrapper.text()).toContain('山田太郎')
    expect(wrapper.text()).toContain('鈴木花子')

    const trashButtons = wrapper
      .findAllComponents({ name: 'Button' })
      .filter((b) => b.props('icon') === 'pi pi-trash' && b.props('severity') === 'danger')
    await trashButtons[0]!.trigger('click')
    await flushPromises()

    const confirmButton = findDialogButton(wrapper, 'Delete')
    await confirmButton!.trigger('click')
    await flushPromises()

    expect(listMembers).toHaveBeenCalledTimes(2)
    expect(wrapper.text()).not.toContain('山田太郎')
    expect(wrapper.text()).toContain('鈴木花子')
  })

  it('MP-006: 削除成功後、削除対象・送信中状態がリセットされる', async () => {
    const wrapper = await mountOnMembersView()
    await findDeleteTriggerButton(wrapper)!.trigger('click')
    await flushPromises()

    const confirmButton = findDialogButton(wrapper, 'Delete')
    await confirmButton!.trigger('click')
    await flushPromises()

    expect(notificationSuccessMock).toHaveBeenCalled()
    const vm = wrapper.vm as unknown as MemberProfilesVM
    expect(vm.showDeleteMemberDialog).toBe(false)
    expect(vm.deleteTargetMember).toBeNull()
    expect(vm.deletingMember).toBe(false)
  })
})
