import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import TeamMemberPermissionGroupsDialog from '~/components/team/TeamMemberPermissionGroupsDialog.vue'
import { primeStubs } from '../organization/member-teams/stubs'

/**
 * F01.2.1 8-D — メンバー一覧の操作メニュー「権限グループ」ダイアログ（§3.2・AC-P03〜P05）。
 *
 *   PD-01 開くと権限グループ一覧を取得し、チェックリストで出す（MANAGE_ORG_AFFILIATION を含むものに印）
 *   PD-02 選んで保存すると assign API を選択 ID 全部で呼び、assigned を発火して閉じる
 *   PD-03 何も選ばずに保存すると空配列（全割当解除）で呼ぶ
 *   PD-04 保存失敗はダイアログ内にエラーを残し、閉じない
 *   PD-05 保存の二重送信を防ぐ
 *   PD-06 一覧の取得失敗はエラー表示し、保存できない
 *   PD-07 グループが 0 件なら案内を出し、保存できない
 *   PD-08 member が null のときは何も取得しない
 */

const getPermissionGroups = vi.fn()
const assignPermissionGroups = vi.fn()

vi.mock('~/composables/team/useTeamMembers', () => ({
  useTeamMembers: () => ({ getPermissionGroups, assignPermissionGroups }),
}))

const stubs = { ...primeStubs }
const member = { userId: 42, displayName: '山田', roleName: 'MEMBER' }
const groups = [
  { id: 1, name: '加盟担当', targetRole: 'MEMBER', permissions: ['MANAGE_ORG_AFFILIATION'] },
  { id: 2, name: '予定担当', targetRole: 'MEMBER', permissions: ['MANAGE_SCHEDULES'] },
]

async function mountDialog(props: Record<string, unknown> = {}) {
  const w = await mountSuspended(TeamMemberPermissionGroupsDialog, {
    props: { visible: true, teamSlug: 'team-1', member, ...props },
    global: { stubs },
  })
  await flushPromises()
  return w
}

describe('TeamMemberPermissionGroupsDialog', () => {
  beforeEach(() => {
    getPermissionGroups.mockReset()
    assignPermissionGroups.mockReset()
    getPermissionGroups.mockResolvedValue({ data: groups })
  })

  it('PD-01: 一覧を取得してチェックリストで出す', async () => {
    const w = await mountDialog()
    expect(getPermissionGroups).toHaveBeenCalledWith('team-1')
    expect(w.find('[data-testid="pg-check-1"]').exists()).toBe(true)
    expect(w.find('[data-testid="pg-check-2"]').exists()).toBe(true)
    expect(w.find('[data-testid="pg-affiliation-mark-1"]').exists()).toBe(true)
    expect(w.find('[data-testid="pg-affiliation-mark-2"]').exists()).toBe(false)
  })

  it('PD-02: 保存で選択 ID を assign API に渡し、assigned を発火', async () => {
    assignPermissionGroups.mockResolvedValue(undefined)
    const w = await mountDialog()
    await w.find('[data-testid="pg-check-1"]').setValue(true)
    await w.find('[data-testid="pg-assign-submit"]').trigger('click')
    await flushPromises()
    expect(assignPermissionGroups).toHaveBeenCalledWith('team-1', 42, [1])
    expect(w.emitted('assigned')).toHaveLength(1)
    expect(w.emitted('update:visible')?.at(-1)).toEqual([false])
  })

  it('PD-03: 何も選ばなければ空配列で呼ぶ', async () => {
    assignPermissionGroups.mockResolvedValue(undefined)
    const w = await mountDialog()
    await w.find('[data-testid="pg-assign-submit"]').trigger('click')
    await flushPromises()
    expect(assignPermissionGroups).toHaveBeenCalledWith('team-1', 42, [])
  })

  it('PD-04: 保存失敗はエラーを残し閉じない', async () => {
    assignPermissionGroups.mockRejectedValue(new Error('403'))
    const w = await mountDialog()
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
})
