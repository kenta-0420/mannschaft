import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import TeamPermissionGroupFormDialog from '~/components/team/TeamPermissionGroupFormDialog.vue'
import { primeStubs } from '../organization/member-teams/stubs'
import { expectedPermissionLabel } from '../../helpers/permissionLabelExpectations'

/**
 * F01.2.1 8-D — チームの権限グループ 作成・編集ダイアログ（§3.2・AC-P03/P04/P10）。
 *
 *   PF-01 開くと権限カタログ（チーム ADMIN+ 用 API）を取得してチェックリストで出す。ADMIN 専用の権限（加盟操作）に印
 *   PF-02 名前・対象ロール・権限を選んで作成すると、POST に {name,targetRole,permissionIds} を渡し saved を発火
 *   PF-03 名前が空・権限が未選択なら保存できない
 *   PF-04 編集では既存の名前・対象ロール・権限（名前→ID に写像）で初期化し、PATCH で更新する
 *   PF-05 保存失敗はダイアログ内にエラーを残し閉じない / 二重送信しない
 *   PF-06 カタログ取得失敗はエラー表示し、保存できない
 *   PF-07 作成→割当の導線: 保存後に「メンバー一覧で割り当てる」案内が出る
 *   PF-08 ADMIN 専用権限を付与できない操作者（SYSTEM_ADMIN 兼任など）は、ADMIN 専用権限を選べず理由が出る（他の権限は選べる）
 *   PF-09 編集対象が既に ADMIN 専用権限を含み、付与できない操作者なら、編集全体を不可にする（外す操作も BE は 403）
 *   PF-10 カタログの assignableTargetRoles に選択中の対象ロールが無い権限（課金権限は DEPUTY_ADMIN のみ）は選べず理由が出る。対象ロールを切り替えると該当の選択は外れる
 *   PF-12 不適合な既存権限（MEMBER 向けグループの課金権限）は「解除のみ可」: チェック済みでも外せ、外すと保存でき、外した後は再チェックできない
 *   PF-11 既存グループが対象ロールと権限の組合せ制約に反している（誰にも割り当てられない）ときは保存させず理由を出す
 */

const listPermissionCatalog = vi.fn()
const createPermissionGroup = vi.fn()
const updatePermissionGroup = vi.fn()

vi.mock('~/composables/usePermissionCatalogApi', () => ({
  usePermissionCatalogApi: () => ({ listPermissionCatalog }),
}))
vi.mock('~/composables/team/useTeamMembers', () => ({
  useTeamMembers: () => ({ createPermissionGroup, updatePermissionGroup }),
}))

const BOTH = ['DEPUTY_ADMIN', 'MEMBER']
const catalog = [
  { id: 1, name: 'MANAGE_ORG_AFFILIATION', displayName: '組織への加盟操作', scope: 'TEAM', adminOnlyGrantable: true, assignableTargetRoles: BOTH },
  { id: 2, name: 'MANAGE_SCHEDULES', displayName: '予定管理', scope: 'TEAM', adminOnlyGrantable: false, assignableTargetRoles: BOTH },
  { id: 3, name: 'MANAGE_TEAM_BILLING', displayName: 'チーム課金管理', scope: 'TEAM', adminOnlyGrantable: false, assignableTargetRoles: ['DEPUTY_ADMIN'] },
]

const stubs = { ...primeStubs, NuxtLink: { props: ['to'], template: '<a :href="to"><slot /></a>' } }

async function mountDialog(props: Record<string, unknown> = {}) {
  const w = await mountSuspended(TeamPermissionGroupFormDialog, {
    props: { visible: true, teamSlug: 'team-1', teamId: 77, group: null, canGrantAdminOnly: true, ...props },
    global: { stubs },
  })
  await flushPromises()
  return w
}

describe('TeamPermissionGroupFormDialog', () => {
  beforeEach(() => {
    listPermissionCatalog.mockReset()
    createPermissionGroup.mockReset()
    updatePermissionGroup.mockReset()
    listPermissionCatalog.mockResolvedValue(catalog)
  })

  it('PF-01: カタログを取得して出し、ADMIN 専用の権限に印を付ける', async () => {
    const w = await mountDialog()
    expect(listPermissionCatalog).toHaveBeenCalledWith('TEAM', 77)
    expect(w.find('[data-testid="pf-perm-1"]').exists()).toBe(true)
    expect(w.find('[data-testid="pf-perm-2"]').exists()).toBe(true)
    expect(w.find('[data-testid="pf-admin-only-1"]').exists()).toBe(true)
    expect(w.find('[data-testid="pf-admin-only-2"]').exists()).toBe(false)
  })

  it('PF-02: 作成すると POST に name/targetRole/permissionIds を渡し saved を発火', async () => {
    createPermissionGroup.mockResolvedValue({})
    const w = await mountDialog()
    await w.find('[data-testid="pf-name"]').setValue('加盟担当')
    await w.find('[data-testid="pf-target-role"]').setValue('MEMBER')
    await w.find('[data-testid="pf-perm-1"]').setValue(true)
    await w.find('[data-testid="pf-submit"]').trigger('click')
    await flushPromises()
    expect(createPermissionGroup).toHaveBeenCalledWith('team-1', {
      name: '加盟担当',
      targetRole: 'MEMBER',
      permissionIds: [1],
    })
    expect(w.emitted('saved')).toHaveLength(1)
  })

  it('PF-03: 名前が空・権限未選択なら保存できない', async () => {
    const w = await mountDialog()
    await w.find('[data-testid="pf-submit"]').trigger('click')
    await w.find('[data-testid="pf-name"]').setValue('x')
    await w.find('[data-testid="pf-submit"]').trigger('click')
    await flushPromises()
    expect(createPermissionGroup).not.toHaveBeenCalled()
  })

  it('PF-04: 編集は既存値で初期化し、PATCH で更新する', async () => {
    updatePermissionGroup.mockResolvedValue({})
    const group = { id: 9, name: '予定担当', targetRole: 'DEPUTY_ADMIN', permissions: ['MANAGE_SCHEDULES'] }
    const w = await mountDialog({ group })
    expect((w.find('[data-testid="pf-name"]').element as HTMLInputElement).value).toBe('予定担当')
    expect((w.find('[data-testid="pf-perm-2"]').element as HTMLInputElement).checked).toBe(true)
    expect((w.find('[data-testid="pf-perm-1"]').element as HTMLInputElement).checked).toBe(false)
    await w.find('[data-testid="pf-perm-1"]').setValue(true)
    await w.find('[data-testid="pf-submit"]').trigger('click')
    await flushPromises()
    expect(updatePermissionGroup).toHaveBeenCalledWith('team-1', 9, {
      name: '予定担当',
      targetRole: 'DEPUTY_ADMIN',
      permissionIds: [1, 2],
    })
    expect(createPermissionGroup).not.toHaveBeenCalled()
  })

  it('PF-05: 保存失敗はエラーを残し、二重送信しない', async () => {
    createPermissionGroup.mockRejectedValue(new Error('403'))
    const w = await mountDialog()
    await w.find('[data-testid="pf-name"]').setValue('a')
    await w.find('[data-testid="pf-perm-2"]').setValue(true)
    await w.find('[data-testid="pf-submit"]').trigger('click')
    await flushPromises()
    expect(w.find('[data-testid="pf-error"]').exists()).toBe(true)
    expect(w.emitted('saved')).toBeUndefined()

    let resolve!: () => void
    createPermissionGroup.mockReset()
    createPermissionGroup.mockReturnValue(new Promise<void>((r) => { resolve = r }))
    const submit = w.find('[data-testid="pf-submit"]')
    await submit.trigger('click')
    await submit.trigger('click')
    expect(createPermissionGroup).toHaveBeenCalledTimes(1)
    resolve()
    await flushPromises()
  })

  it('PF-06: カタログ取得失敗はエラー表示し、保存できない', async () => {
    listPermissionCatalog.mockRejectedValue(new Error('boom'))
    const w = await mountDialog()
    expect(w.find('[data-testid="pf-load-error"]').exists()).toBe(true)
    expect(w.find('[data-testid="pf-submit"]').exists()).toBe(false)
  })

  it('PF-08: 付与できない操作者は ADMIN 専用権限を選べず、理由が出る。他の権限は選べる', async () => {
    createPermissionGroup.mockResolvedValue({})
    const w = await mountDialog({ canGrantAdminOnly: false })
    expect((w.find('[data-testid="pf-perm-1"]').element as HTMLInputElement).disabled).toBe(true)
    expect((w.find('[data-testid="pf-perm-2"]').element as HTMLInputElement).disabled).toBe(false)
    expect(w.find('[data-testid="pf-admin-only-reason"]').exists()).toBe(true)
    await w.find('[data-testid="pf-name"]').setValue('予定')
    await w.find('[data-testid="pf-perm-2"]').setValue(true)
    await w.find('[data-testid="pf-submit"]').trigger('click')
    await flushPromises()
    expect(createPermissionGroup).toHaveBeenCalledWith('team-1', { name: '予定', targetRole: 'MEMBER', permissionIds: [2] })
  })

  it('PF-09: 既に ADMIN 専用権限を含むグループは、付与できない操作者には編集させない', async () => {
    const group = { id: 9, name: '加盟担当', targetRole: 'MEMBER', permissions: ['MANAGE_ORG_AFFILIATION'] }
    const w = await mountDialog({ group, canGrantAdminOnly: false })
    expect(w.find('[data-testid="pf-admin-only-locked"]').exists()).toBe(true)
    expect(w.find('[data-testid="pf-submit"]').exists()).toBe(false)
    expect((w.find('[data-testid="pf-name"]').element as HTMLInputElement).disabled).toBe(true)
    expect(updatePermissionGroup).not.toHaveBeenCalled()
  })

  it('PF-10: 対象ロールに付与できない権限（課金権限は DEPUTY_ADMIN のみ）は選べず、ロール切替で選択が外れる', async () => {
    createPermissionGroup.mockResolvedValue({})
    const w = await mountDialog()
    // 既定の対象ロールは MEMBER: 課金権限は選べない
    expect((w.find('[data-testid="pf-perm-3"]').element as HTMLInputElement).disabled).toBe(true)
    expect(w.find('[data-testid="pf-role-reason-3"]').exists()).toBe(true)
    expect((w.find('[data-testid="pf-perm-2"]').element as HTMLInputElement).disabled).toBe(false)
    // DEPUTY_ADMIN にすると選べる
    await w.find('[data-testid="pf-target-role"]').setValue('DEPUTY_ADMIN')
    expect((w.find('[data-testid="pf-perm-3"]').element as HTMLInputElement).disabled).toBe(false)
    await w.find('[data-testid="pf-perm-3"]').setValue(true)
    expect((w.find('[data-testid="pf-perm-3"]').element as HTMLInputElement).checked).toBe(true)
    // MEMBER に戻すと、付与できなくなった選択は外れる
    await w.find('[data-testid="pf-target-role"]').setValue('MEMBER')
    expect((w.find('[data-testid="pf-perm-3"]').element as HTMLInputElement).checked).toBe(false)
  })

  it('PF-11: 既に組合せ制約に反しているグループは保存させず、理由を出す', async () => {
    const group = { id: 9, name: '課金', targetRole: 'MEMBER', permissions: ['MANAGE_TEAM_BILLING'] }
    const w = await mountDialog({ group })
    expect(w.find('[data-testid="pf-role-conflict"]').exists()).toBe(true)
    await w.find('[data-testid="pf-submit"]').trigger('click')
    await flushPromises()
    expect(updatePermissionGroup).not.toHaveBeenCalled()
  })

  it('PF-12: 不適合な既存権限は外せて、外すと保存でき、外した後は再チェックできない', async () => {
    updatePermissionGroup.mockResolvedValue({})
    const group = { id: 9, name: '予定+課金', targetRole: 'MEMBER', permissions: ['MANAGE_SCHEDULES', 'MANAGE_TEAM_BILLING'] }
    const w = await mountDialog({ group })
    const box = (id: number) => w.find(`[data-testid="pf-perm-${id}"]`)
    // 不整合（課金×MEMBER）。チェック済みの課金権限は無効ではなく外せる。保存はできない
    expect(w.find('[data-testid="pf-role-conflict"]').exists()).toBe(true)
    expect((box(3).element as HTMLInputElement).checked).toBe(true)
    expect((box(3).element as HTMLInputElement).disabled).toBe(false)
    await w.find('[data-testid="pf-submit"]').trigger('click')
    await flushPromises()
    expect(updatePermissionGroup).not.toHaveBeenCalled()
    // 外すと整合して保存できる
    await box(3).setValue(false)
    expect(w.find('[data-testid="pf-role-conflict"]').exists()).toBe(false)
    expect((box(3).element as HTMLInputElement).disabled).toBe(true)
    await w.find('[data-testid="pf-submit"]').trigger('click')
    await flushPromises()
    expect(updatePermissionGroup).toHaveBeenCalledWith('team-1', 9, {
      name: '予定+課金',
      targetRole: 'MEMBER',
      permissionIds: [2],
    })
  })

  it('PF-07: 保存後に「メンバー一覧で割り当てる」案内が出る', async () => {
    createPermissionGroup.mockResolvedValue({})
    const w = await mountDialog()
    await w.find('[data-testid="pf-name"]').setValue('加盟担当')
    await w.find('[data-testid="pf-perm-1"]').setValue(true)
    await w.find('[data-testid="pf-submit"]').trigger('click')
    await flushPromises()
    expect(w.find('[data-testid="pf-go-members"]').attributes('href')).toBe('/teams/team-1/members')
  })
})

/** テスト環境の既定ロケールに依存しないよう、明示的に切り替えて再描画を待つ。 */
async function switchLocale(w: { vm: unknown }, locale: 'ja' | 'en'): Promise<void> {
  const i18n = (w.vm as { $i18n: { locale: string, setLocale?: (l: string) => Promise<void> } }).$i18n
  if (i18n.setLocale) await i18n.setLocale(locale)
  else i18n.locale = locale
  for (let i = 0; i < 10; i++) {
    await flushPromises()
    await new Promise(r => setTimeout(r, 25))
  }
}

/**
 * CMP-261011-0035 ALIC-2 — AC-ALIC2-04: ダイアログの権限名もカタログの displayName（DB 日本語固定）ではなく、
 * 一覧と同じ i18n の訳で出す。displayName='X' を渡しても表示は訳になる。
 */
describe('TeamPermissionGroupFormDialog — 権限名の表示（ALIC-2）', () => {
  beforeEach(() => {
    listPermissionCatalog.mockReset()
    listPermissionCatalog.mockResolvedValue([
      { id: 2, name: 'MANAGE_SCHEDULES', displayName: 'X', scope: 'TEAM', adminOnlyGrantable: false, assignableTargetRoles: BOTH },
      { id: 9, name: 'jobs.manage', displayName: 'X', scope: 'TEAM', adminOnlyGrantable: false, assignableTargetRoles: BOTH },
    ])
  })

  it('AC-ALIC2-04: カタログの displayName を使わず、一覧と同じ i18n の訳を出す', async () => {
    const w = await mountDialog()
    await switchLocale(w, 'ja')
    const spans = w.findAll('span').map(s => s.text().trim())
    expect(spans).not.toContain('X')
    for (const code of ['MANAGE_SCHEDULES', 'jobs.manage']) {
      const label = expectedPermissionLabel('ja', code)
      expect(label, `ja の teamAffiliations.permission_names に ${code} の訳が無い`).toBeTypeOf('string')
      expect(spans).toContain(label)
    }
  })
})
