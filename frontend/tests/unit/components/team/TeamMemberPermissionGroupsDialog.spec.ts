import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import TeamMemberPermissionGroupsDialog from '~/components/team/TeamMemberPermissionGroupsDialog.vue'
import { primeStubs } from '../organization/member-teams/stubs'

/**
 * F01.2.1 8-D — メンバー一覧の操作メニュー「権限グループ」ダイアログ（§3.2・AC-P03〜P05）。
 *
 * BE の PUT は割当の全置換なので、現在の割当（メンバー一覧の permissionGroups）で初期化し、
 * 追加・削除の差分を見せてから保存する（既存割当を黙って消さない）。
 *
 *   PD-01 開くと一覧を取得し、現在の割当にチェックが入った状態で出る（MANAGE_ORG_AFFILIATION を含むものに印）
 *   PD-02 既存割当を保持したまま1件追加すると、PUT は既存+追加の全 ID で呼ばれ、差分に追加が出る
 *   PD-03 変更が無ければ保存できない（無用な全置換をしない）
 *   PD-04 保存失敗はダイアログ内にエラーを残し、閉じない
 *   PD-05 保存の二重送信を防ぐ
 *   PD-06 一覧の取得失敗はエラー表示し、保存できない
 *   PD-07 グループが 0 件なら案内を出し、保存できない
 *   PD-08 member が null のときは何も取得しない
 *   PD-09 既存割当を外すと差分に削除が出て、PUT から除かれる（全部外すと空配列）
 *   PD-10 現在の割当が分からない（permissionGroups 未提供）ときは保存させない
 *   PD-11 別メンバーを開き直したら、そのメンバーの割当で初期化される
 *   PD-12 ADMIN 専用権限を含むグループを付与できない操作者（SYSTEM_ADMIN 兼任など）には、そのグループを選べなくし理由を出す
 *   PD-13 ADMIN 専用権限を含むグループが既に割り当て済みのメンバーは、付与できない操作者には一切変更させない（外す操作も BE は 403）
 *   PD-14 付与できる操作者（チーム ADMIN）は ADMIN 専用権限のグループも選べる
 *   PD-16 割当済みで targetRole が不一致になったグループは「解除のみ可」: 外せる・再チェック不可・外さずには保存不可（外すよう案内）。外せば保存でき、PUT から除かれる
 *   PD-15 対象メンバーのロールと targetRole が一致するグループだけ選べる（BE は不一致を 404 にし保存全体が失敗する）。不一致は理由つきで無効
 */

const getPermissionGroups = vi.fn()
const assignPermissionGroups = vi.fn()

vi.mock('~/composables/team/useTeamMembers', () => ({
  useTeamMembers: () => ({ getPermissionGroups, assignPermissionGroups }),
}))

const stubs = { ...primeStubs }
const member = {
  userId: 42,
  displayName: '山田',
  roleName: 'MEMBER',
  permissionGroups: [{ id: 2, name: '予定担当' }],
}
const groups = [
  { id: 1, name: '加盟担当', targetRole: 'MEMBER', permissions: ['MANAGE_ORG_AFFILIATION'] },
  { id: 2, name: '予定担当', targetRole: 'MEMBER', permissions: ['MANAGE_SCHEDULES'] },
]

async function mountDialog(props: Record<string, unknown> = {}) {
  const w = await mountSuspended(TeamMemberPermissionGroupsDialog, {
    props: { visible: true, teamSlug: 'team-1', member, canGrantAdminOnly: true, ...props },
    global: { stubs },
  })
  await flushPromises()
  return w
}

const checked = (w: Awaited<ReturnType<typeof mountDialog>>, id: number) =>
  (w.find(`[data-testid="pg-check-${id}"]`).element as HTMLInputElement).checked

describe('TeamMemberPermissionGroupsDialog', () => {
  beforeEach(() => {
    getPermissionGroups.mockReset()
    assignPermissionGroups.mockReset()
    getPermissionGroups.mockResolvedValue({ data: groups })
  })

  it('PD-01: 現在の割当にチェックが入った状態でチェックリストが出る', async () => {
    const w = await mountDialog()
    expect(getPermissionGroups).toHaveBeenCalledWith('team-1')
    expect(checked(w, 2)).toBe(true)
    expect(checked(w, 1)).toBe(false)
    expect(w.find('[data-testid="pg-affiliation-mark-1"]').exists()).toBe(true)
    expect(w.find('[data-testid="pg-affiliation-mark-2"]').exists()).toBe(false)
  })

  it('PD-02: 既存割当を保持して1件追加すると、既存+追加の全 ID で PUT し、差分に追加が出る', async () => {
    assignPermissionGroups.mockResolvedValue(undefined)
    const w = await mountDialog()
    await w.find('[data-testid="pg-check-1"]').setValue(true)
    expect(w.find('[data-testid="pg-diff-add"]').text()).toContain('加盟担当')
    expect(w.find('[data-testid="pg-diff-remove"]').exists()).toBe(false)
    await w.find('[data-testid="pg-assign-submit"]').trigger('click')
    await flushPromises()
    expect(assignPermissionGroups).toHaveBeenCalledWith('team-1', 42, [1, 2])
    expect(w.emitted('assigned')).toHaveLength(1)
    expect(w.emitted('update:visible')?.at(-1)).toEqual([false])
  })

  it('PD-03: 変更が無ければ保存できない', async () => {
    const w = await mountDialog()
    await w.find('[data-testid="pg-assign-submit"]').trigger('click')
    await flushPromises()
    expect(assignPermissionGroups).not.toHaveBeenCalled()
  })

  it('PD-04: 保存失敗はエラーを残し閉じない', async () => {
    assignPermissionGroups.mockRejectedValue(new Error('403'))
    const w = await mountDialog()
    await w.find('[data-testid="pg-check-1"]').setValue(true)
    await w.find('[data-testid="pg-assign-submit"]').trigger('click')
    await flushPromises()
    expect(w.find('[data-testid="pg-assign-error"]').exists()).toBe(true)
    expect(w.emitted('assigned')).toBeUndefined()
    expect(w.emitted('update:visible')).toBeUndefined()
  })

  it('PD-05: 二重送信を防ぐ', async () => {
    let resolve!: () => void
    assignPermissionGroups.mockReturnValue(new Promise<void>((r) => { resolve = r }))
    const w = await mountDialog()
    await w.find('[data-testid="pg-check-1"]').setValue(true)
    const submit = w.find('[data-testid="pg-assign-submit"]')
    await submit.trigger('click')
    await submit.trigger('click')
    expect(assignPermissionGroups).toHaveBeenCalledTimes(1)
    resolve()
    await flushPromises()
  })

  it('PD-06: 取得失敗はエラー表示し、保存できない', async () => {
    getPermissionGroups.mockRejectedValue(new Error('boom'))
    const w = await mountDialog()
    expect(w.find('[data-testid="pg-load-error"]').exists()).toBe(true)
    expect(w.find('[data-testid="pg-assign-submit"]').exists()).toBe(false)
  })

  it('PD-07: 0 件なら案内を出し、保存できない', async () => {
    getPermissionGroups.mockResolvedValue({ data: [] })
    const w = await mountDialog()
    expect(w.find('[data-testid="pg-empty"]').exists()).toBe(true)
    expect(w.find('[data-testid="pg-assign-submit"]').exists()).toBe(false)
  })

  it('PD-08: member が null なら取得しない', async () => {
    await mountDialog({ member: null })
    expect(getPermissionGroups).not.toHaveBeenCalled()
  })

  it('PD-09: 既存割当を外すと差分に削除が出て、PUT から除かれる（全部外すと空配列）', async () => {
    assignPermissionGroups.mockResolvedValue(undefined)
    const w = await mountDialog()
    await w.find('[data-testid="pg-check-2"]').setValue(false)
    expect(w.find('[data-testid="pg-diff-remove"]').text()).toContain('予定担当')
    await w.find('[data-testid="pg-assign-submit"]').trigger('click')
    await flushPromises()
    expect(assignPermissionGroups).toHaveBeenCalledWith('team-1', 42, [])
  })

  it('PD-10: 現在の割当が分からないときは保存させない', async () => {
    const w = await mountDialog({ member: { userId: 42, displayName: '山田', roleName: 'MEMBER' } })
    expect(w.find('[data-testid="pg-current-unknown"]').exists()).toBe(true)
    expect(w.find('[data-testid="pg-assign-submit"]').exists()).toBe(false)
  })

  const adminOnlyGroups = [
    { id: 1, name: '加盟担当', targetRole: 'MEMBER', permissions: ['MANAGE_ORG_AFFILIATION'], containsAdminOnlyPermission: true },
    { id: 2, name: '予定担当', targetRole: 'MEMBER', permissions: ['MANAGE_SCHEDULES'], containsAdminOnlyPermission: false },
  ]

  it('PD-12: 付与できない操作者には ADMIN 専用権限のグループを選べなくし、理由を出す', async () => {
    getPermissionGroups.mockResolvedValue({ data: adminOnlyGroups })
    const w = await mountDialog({ canGrantAdminOnly: false })
    expect((w.find('[data-testid="pg-check-1"]').element as HTMLInputElement).disabled).toBe(true)
    expect((w.find('[data-testid="pg-check-2"]').element as HTMLInputElement).disabled).toBe(false)
    expect(w.find('[data-testid="pg-admin-only-reason"]').exists()).toBe(true)
  })

  it('PD-13: ADMIN 専用権限のグループが割り当て済みなら、付与できない操作者には一切変更させない', async () => {
    getPermissionGroups.mockResolvedValue({ data: adminOnlyGroups })
    const w = await mountDialog({
      canGrantAdminOnly: false,
      member: { ...member, permissionGroups: [{ id: 1, name: '加盟担当' }] },
    })
    expect((w.find('[data-testid="pg-check-2"]').element as HTMLInputElement).disabled).toBe(true)
    expect((w.find('[data-testid="pg-check-1"]').element as HTMLInputElement).disabled).toBe(true)
    expect(w.find('[data-testid="pg-admin-only-locked"]').exists()).toBe(true)
    expect(w.find('[data-testid="pg-assign-submit"]').exists()).toBe(false)
    expect(assignPermissionGroups).not.toHaveBeenCalled()
  })

  it('PD-14: 付与できる操作者（チーム ADMIN）は ADMIN 専用権限のグループも選べる', async () => {
    getPermissionGroups.mockResolvedValue({ data: adminOnlyGroups })
    assignPermissionGroups.mockResolvedValue(undefined)
    const w = await mountDialog({ canGrantAdminOnly: true })
    expect((w.find('[data-testid="pg-check-1"]').element as HTMLInputElement).disabled).toBe(false)
    expect(w.find('[data-testid="pg-admin-only-reason"]').exists()).toBe(false)
    await w.find('[data-testid="pg-check-1"]').setValue(true)
    await w.find('[data-testid="pg-assign-submit"]').trigger('click')
    await flushPromises()
    expect(assignPermissionGroups).toHaveBeenCalledWith('team-1', 42, [1, 2])
  })

  it('PD-15: メンバーのロールと targetRole が不一致のグループは選べず、理由を出す', async () => {
    getPermissionGroups.mockResolvedValue({
      data: [
        { id: 1, name: '副管理者向け', targetRole: 'DEPUTY_ADMIN', permissions: ['MANAGE_TEAM_BILLING'] },
        { id: 2, name: 'メンバー向け', targetRole: 'MEMBER', permissions: ['MANAGE_SCHEDULES'] },
      ],
    })
    const w = await mountDialog({ member: { userId: 42, displayName: '山田', roleName: 'MEMBER', permissionGroups: [] } })
    expect((w.find('[data-testid="pg-check-1"]').element as HTMLInputElement).disabled).toBe(true)
    expect((w.find('[data-testid="pg-check-2"]').element as HTMLInputElement).disabled).toBe(false)
    expect(w.find('[data-testid="pg-role-mismatch-1"]').exists()).toBe(true)
    expect(w.find('[data-testid="pg-role-mismatch-2"]').exists()).toBe(false)

    const deputy = await mountDialog({ member: { userId: 43, displayName: '佐藤', roleName: 'DEPUTY_ADMIN', permissionGroups: [] } })
    expect((deputy.find('[data-testid="pg-check-1"]').element as HTMLInputElement).disabled).toBe(false)
    expect((deputy.find('[data-testid="pg-check-2"]').element as HTMLInputElement).disabled).toBe(true)
  })

  it('PD-16: 不一致になった割当済みグループは外せるが再チェックできず、外さないと保存できない', async () => {
    getPermissionGroups.mockResolvedValue({
      data: [
        { id: 1, name: '副管理者向け', targetRole: 'DEPUTY_ADMIN', permissions: ['X'] },
        { id: 2, name: 'メンバー向け', targetRole: 'MEMBER', permissions: ['Y'] },
      ],
    })
    assignPermissionGroups.mockResolvedValue(undefined)
    // メンバー(MEMBER)に、後から targetRole が DEPUTY_ADMIN に変わったグループ 1 が割当済み
    const w = await mountDialog({
      member: { userId: 42, displayName: '山田', roleName: 'MEMBER', permissionGroups: [{ id: 1, name: '副管理者向け' }] },
    })
    const box = (id: number) => w.find(`[data-testid="pg-check-${id}"]`)
    // チェック済みでも解除できる（無効ではない）
    expect((box(1).element as HTMLInputElement).checked).toBe(true)
    expect((box(1).element as HTMLInputElement).disabled).toBe(false)
    // 他の変更（グループ 2 を追加）をしても、不一致 ID が残っている間は保存できず案内が出る
    await box(2).setValue(true)
    expect(w.find('[data-testid="pg-role-mismatch-remove"]').exists()).toBe(true)
    await w.find('[data-testid="pg-assign-submit"]').trigger('click')
    await flushPromises()
    expect(assignPermissionGroups).not.toHaveBeenCalled()
    // 外すと保存でき、PUT から不一致 ID が除かれる
    await box(1).setValue(false)
    expect(w.find('[data-testid="pg-role-mismatch-remove"]').exists()).toBe(false)
    await w.find('[data-testid="pg-assign-submit"]').trigger('click')
    await flushPromises()
    expect(assignPermissionGroups).toHaveBeenCalledWith('team-1', 42, [2])
    // 外した後は再チェックできない
    expect((box(1).element as HTMLInputElement).disabled).toBe(true)
  })

  it('PD-11: 別メンバーを開き直したら、そのメンバーの割当で初期化される', async () => {
    const w = await mountDialog()
    await w.setProps({ member: { userId: 43, displayName: '佐藤', roleName: 'MEMBER', permissionGroups: [{ id: 1, name: '加盟担当' }] } })
    await flushPromises()
    expect(checked(w, 1)).toBe(true)
    expect(checked(w, 2)).toBe(false)
  })
})
