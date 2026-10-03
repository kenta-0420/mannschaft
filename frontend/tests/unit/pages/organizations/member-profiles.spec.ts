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
 * PR #3589 検分第1巡の根治:
 *   - 実際の削除ボタン・キャンセル/確定ボタンを `trigger('click')` で操作し、
 *     ボタンの配線（@click の結び先）自体を検証する（VM直接操作では配線ミスを検出できない）
 *   - API 失敗時にエラーが通知され、ダイアログ・削除対象が握りつぶされず残ること
 *
 * PR #3589 検分第2巡の根治:
 *   - 「ダイアログに表示している対象」（showDeleteMemberDialog/deleteTargetMember）と
 *     「実際に API 送信中の ID」（deletingMemberId）を分離した設計そのものを検証する。
 *     解決しない Promise で送信を保留した状態で、ダイアログを閉じる・別メンバーの
 *     削除ボタンを押すができること、それでも新しい送信は始まらないこと、
 *     保留中の送信が完了しても別メンバーのダイアログ状態が消されないことを確かめる
 *     （MP-003）。これは第1巡の指摘（別メンバーの確認状態の上書き）と、第2巡の指摘
 *     （応答が返らないと画面を塞いだままになる）の両方を同時に防ぐ設計になっている。
 *   - 削除対象ボタン・キャンセル/確定ボタンは data-testid で探す（ロケールに依存しない）。
 *   - deleteMember に渡される ID を厳密に検証し、再取得モックも「削除 API に渡された ID を
 *     一覧から除く」動的な作りにする（固定で特定の表示名を消す決め打ちをやめる）。
 *
 * 検証観点:
 *   MP-001 削除ボタンを押すと確認ダイアログが表示される（deleteMember は呼ばれない）
 *   MP-002 キャンセルボタンを押すと deleteMember が呼ばれない・ダイアログが閉じる
 *   MP-003 送信中でもダイアログは閉じられ、別メンバーの確認ダイアログも開けるが、
 *          その確定ボタンは無効化され新しい送信は始まらない。保留中の送信が完了しても
 *          別メンバーの確認ダイアログは消されず、その後は確定できる
 *   MP-004 API が失敗したときエラーが通知され、ダイアログ・削除対象が残り、
 *          キャンセルしてから再試行すると成功する
 *   MP-005 deleteMember に渡される ID が選んだメンバーのものであり1回だけ呼ばれる。
 *          一覧の再取得は「削除 API に渡された ID」を除いた結果を返し、そのメンバーだけが
 *          消える。削除対象・送信中状態も成功後にリセットされる
 */

const notificationSuccessMock = vi.fn()
const notificationErrorMock = vi.fn()
const captureQuietMock = vi.fn()

const deleteMember = vi.fn(async (_id: number) => {})
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
// truthy のまま通過してしまうため、実物と同じ ref 相当の形にする。
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

const MEMBER_A = makeMember({ id: 10, displayName: '山田太郎' })
const MEMBER_B = makeMember({ id: 20, displayName: '鈴木花子' })

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
  deletingMemberId: number | null
}

/** 削除トリガー（メンバーカードのゴミ箱ボタン）を data-testid で探す（ロケールに依存しない）。 */
function findDeleteTrigger(wrapper: VueWrapper, id: number) {
  return wrapper
    .findAllComponents({ name: 'Button' })
    .find((b) => b.attributes('data-testid') === `member-card-delete-${id}`)
}

/** 削除確認ダイアログのキャンセルボタンを data-testid で探す。 */
function findDialogCancel(wrapper: VueWrapper) {
  return wrapper
    .findAllComponents({ name: 'Button' })
    .find((b) => b.attributes('data-testid') === 'member-delete-confirm-cancel')
}

/** 削除確認ダイアログの確定ボタンを data-testid で探す。 */
function findDialogSubmit(wrapper: VueWrapper) {
  return wrapper
    .findAllComponents({ name: 'Button' })
    .find((b) => b.attributes('data-testid') === 'member-delete-confirm-submit')
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
    listMembers.mockResolvedValue({
      data: [MEMBER_A, MEMBER_B],
      meta: { total: 2, page: 0, size: 100, totalPages: 1 },
    })
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

    const trigger = findDeleteTrigger(wrapper, MEMBER_A.id)
    expect(trigger).toBeTruthy()
    await trigger!.trigger('click')
    await flushPromises()

    const vm = wrapper.vm as unknown as MemberProfilesVM
    expect(vm.showDeleteMemberDialog).toBe(true)
    expect(vm.deleteTargetMember?.id).toBe(MEMBER_A.id)
    expect(deleteMember).not.toHaveBeenCalled()
  })

  it('MP-002: キャンセルボタンを押すと deleteMember が呼ばれずダイアログが閉じる', async () => {
    const wrapper = await mountOnMembersView()

    await findDeleteTrigger(wrapper, MEMBER_A.id)!.trigger('click')
    await flushPromises()

    const cancelButton = findDialogCancel(wrapper)
    expect(cancelButton).toBeTruthy()
    await cancelButton!.trigger('click')
    await flushPromises()

    expect(deleteMember).not.toHaveBeenCalled()
    const vm = wrapper.vm as unknown as MemberProfilesVM
    expect(vm.showDeleteMemberDialog).toBe(false)
  })

  it('MP-003: 送信中でも閉じられ、別メンバーを選べるが新しい送信は始まらない。保留中の送信完了後も別メンバーのダイアログは消されない', async () => {
    let resolveA!: () => void
    deleteMember.mockImplementation((id: number) => {
      if (id === MEMBER_A.id) {
        return new Promise<void>((resolve) => { resolveA = resolve })
      }
      return Promise.resolve()
    })

    const wrapper = await mountOnMembersView()

    // A の削除を確定して送信中にする
    await findDeleteTrigger(wrapper, MEMBER_A.id)!.trigger('click')
    await flushPromises()
    await findDialogSubmit(wrapper)!.trigger('click')
    await flushPromises()

    expect(deleteMember).toHaveBeenCalledTimes(1)
    expect(deleteMember).toHaveBeenCalledWith(MEMBER_A.id)

    // 送信中でもキャンセル（×・Escape 相当）でダイアログを閉じられる
    await findDialogCancel(wrapper)!.trigger('click')
    await flushPromises()
    let vm = wrapper.vm as unknown as MemberProfilesVM
    expect(vm.showDeleteMemberDialog).toBe(false)

    // 別メンバー（B）の削除ボタンを押すとダイアログは開く
    await findDeleteTrigger(wrapper, MEMBER_B.id)!.trigger('click')
    await flushPromises()
    vm = wrapper.vm as unknown as MemberProfilesVM
    expect(vm.showDeleteMemberDialog).toBe(true)
    expect(vm.deleteTargetMember?.id).toBe(MEMBER_B.id)

    // だが確定ボタンは無効化されている（A が送信中のため）。
    // PrimeVue Button の disabled は内部で宣言された component prop ではなく
    // 素通りする attrs のため props() では読めない。実際に描画される DOM 属性で確認する。
    const submitForB = findDialogSubmit(wrapper)!
    expect(submitForB.attributes('disabled')).toBeDefined()

    // 無効化されたボタンを押しても（クリックが発火しても）新しい送信は始まらない。
    await submitForB.trigger('click')
    await flushPromises()
    expect(deleteMember).toHaveBeenCalledTimes(1)

    // disabled 属性はブラウザ/jsdom がクリックの発火自体を止めてしまうため、上のクリックだけでは
    // executeDeleteMember 内部のガード（`if (deletingMemberId.value != null) return`）は
    // 実際には実行されない。UI（disabled）だけでなくコード側のガードも効いていることを、
    // VM を直接呼び出して検証する（ボタン操作を迂回した防御の二重化を確かめる）。
    // 「ガードを外すと赤くなるか」は、このアサーションのために executeDeleteMember 冒頭の
    // ガード行を一時的にコメントアウトして手元で確認した。外した状態では deleteMember が
    // B の ID で2回目呼ばれ、このアサーションが落ちることを確認済み。確認後ガードを復元した
    // うえでこのテストをコミットしている。
    const vmDirect = wrapper.vm as unknown as { executeDeleteMember: () => Promise<void> }
    await vmDirect.executeDeleteMember()
    await flushPromises()
    expect(deleteMember).toHaveBeenCalledTimes(1)

    // A の送信が完了する
    resolveA()
    await flushPromises()

    // 成功処理は A の送信に対するものなので、B のダイアログ状態は消されない
    vm = wrapper.vm as unknown as MemberProfilesVM
    expect(vm.showDeleteMemberDialog).toBe(true)
    expect(vm.deleteTargetMember?.id).toBe(MEMBER_B.id)
    expect(vm.deletingMemberId).toBeNull()

    // A の送信が終わった後は、B の確定が実行できる
    await findDialogSubmit(wrapper)!.trigger('click')
    await flushPromises()
    expect(deleteMember).toHaveBeenCalledTimes(2)
    expect(deleteMember).toHaveBeenLastCalledWith(MEMBER_B.id)
  })

  it('MP-004: API が失敗したときエラーが通知され、ダイアログ・削除対象が残り、キャンセルしてから再試行すると成功する', async () => {
    deleteMember.mockRejectedValueOnce({ statusCode: 500, data: {} })
    deleteMember.mockResolvedValueOnce(undefined)

    const wrapper = await mountOnMembersView()
    await findDeleteTrigger(wrapper, MEMBER_A.id)!.trigger('click')
    await flushPromises()
    await findDialogSubmit(wrapper)!.trigger('click')
    await flushPromises()

    expect(deleteMember).toHaveBeenCalledTimes(1)
    expect(notificationErrorMock).toHaveBeenCalled()
    expect(notificationSuccessMock).not.toHaveBeenCalled()

    let vm = wrapper.vm as unknown as MemberProfilesVM
    // 握りつぶして閉じるのではなく、ダイアログ・削除対象は残り、送信中フラグのみ解除される
    expect(vm.showDeleteMemberDialog).toBe(true)
    expect(vm.deleteTargetMember?.id).toBe(MEMBER_A.id)
    expect(vm.deletingMemberId).toBeNull()

    // キャンセルできる
    await findDialogCancel(wrapper)!.trigger('click')
    await flushPromises()
    vm = wrapper.vm as unknown as MemberProfilesVM
    expect(vm.showDeleteMemberDialog).toBe(false)

    // 再試行: 再度開いて確定すると今度は成功する
    await findDeleteTrigger(wrapper, MEMBER_A.id)!.trigger('click')
    await flushPromises()
    await findDialogSubmit(wrapper)!.trigger('click')
    await flushPromises()

    expect(deleteMember).toHaveBeenCalledTimes(2)
    expect(notificationSuccessMock).toHaveBeenCalled()
  })

  it('MP-005: deleteMember に選んだメンバーの ID が1回だけ渡され、その ID が一覧から消え、状態がリセットされる', async () => {
    // 再取得モックは「削除 API に渡された ID」を一覧から動的に除く作りにする
    // （固定で特定の表示名を消す決め打ちをやめる）。
    let currentMembers: MemberProfile[] = [MEMBER_A, MEMBER_B]
    deleteMember.mockReset()
    deleteMember.mockImplementation(async (id: number) => {
      currentMembers = currentMembers.filter((m) => m.id !== id)
    })
    listMembers.mockReset()
    listMembers.mockImplementation(async () => ({
      data: currentMembers,
      meta: { total: currentMembers.length, page: 0, size: 100, totalPages: 1 },
    }))

    const wrapper = await mountOnMembersView()
    expect(wrapper.text()).toContain('山田太郎')
    expect(wrapper.text()).toContain('鈴木花子')

    // B（鈴木花子）を選ぶ。固定で A を消すのではなく、選んだ対象の ID が渡ることを確かめる。
    await findDeleteTrigger(wrapper, MEMBER_B.id)!.trigger('click')
    await flushPromises()
    await findDialogSubmit(wrapper)!.trigger('click')
    await flushPromises()

    expect(deleteMember).toHaveBeenCalledTimes(1)
    expect(deleteMember).toHaveBeenCalledWith(MEMBER_B.id)
    expect(notificationSuccessMock).toHaveBeenCalled()

    expect(wrapper.text()).toContain('山田太郎')
    expect(wrapper.text()).not.toContain('鈴木花子')

    const vm = wrapper.vm as unknown as MemberProfilesVM
    expect(vm.showDeleteMemberDialog).toBe(false)
    expect(vm.deleteTargetMember).toBeNull()
    expect(vm.deletingMemberId).toBeNull()
  })
})
