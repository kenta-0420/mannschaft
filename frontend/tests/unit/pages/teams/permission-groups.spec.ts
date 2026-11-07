import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { computed } from 'vue'
import TeamPermissionGroupsPage from '~/pages/teams/[slug]/permission-groups.vue'
import { expectedPermissionLabel, expectedTargetRoleLabel } from '../../helpers/permissionLabelExpectations'

/**
 * F01.2.1 8-D — /teams/{slug}/permission-groups（チーム側の権限グループ。§3.2）。
 *
 *   TPG-01 チーム ADMIN 割当・管理レンズ ON でなければ画面を出さず、API も呼ばない（AC-K03）
 *   TPG-02 許可されていれば一覧を取得して OrgPermissionGroupList に渡す
 *   TPG-03 取得失敗は空に見せずエラー表示し、再試行できる
 *   TPG-05 「グループを作成」で作成ダイアログが開き、保存後に一覧を取り直す（AC-P03/P04 の作成の入口）
 *   TPG-06 一覧の編集ボタンで、そのグループの編集ダイアログが開く
 *   TPG-07 作成後にメンバー一覧（割当の入口）へのリンクがある（作成→割当の導線）
 *   TPG-08 ADMIN 専用権限を付与できない操作者には理由の案内を出し、作成ダイアログへ canGrantAdminOnly=false を渡す
 */

const state = { canGroups: true, canGrant: true }
const getPermissionGroups = vi.fn()

vi.mock('~/composables/useTeamShellContext', () => ({
  useTeamShellContext: () => ({
    canManagePermissionGroups: computed(() => state.canGroups),
    canGrantAdminOnlyPermissions: computed(() => state.canGrant),
    displayName: computed(() => 'チームA'),
    team: computed(() => ({ numericId: 77 })),
  }),
}))
vi.mock('~/composables/team/useTeamMembers', () => ({
  useTeamMembers: () => ({ getPermissionGroups }),
}))
mockNuxtImport('useRoute', () => () => ({ params: { slug: 'team-1' }, query: {}, path: '/teams/team-1/permission-groups' }))

const stubs = {
  NuxtLink: { props: ['to'], template: '<a :href="to"><slot /></a>' },
  OrgPermissionGroupList: {
    props: ['groups', 'editable'],
    emits: ['edit'],
    template: '<ul data-testid="group-list"><li v-for="g in groups" :key="g.id">{{ g.name }}<button :data-testid="`group-edit-${g.id}`" @click="$emit(\'edit\', g)" /></li></ul>',
  },
  TeamPermissionGroupFormDialog: {
    props: ['visible', 'teamSlug', 'teamId', 'group', 'canGrantAdminOnly'],
    emits: ['saved', 'update:visible'],
    template: '<div v-if="visible" data-testid="group-form" :data-group-id="group?.id" :data-team-id="teamId" :data-can-grant="canGrantAdminOnly"><button data-testid="group-form-saved" @click="$emit(\'saved\')" /></div>',
  },
}

async function mountPage() {
  const w = await mountSuspended(TeamPermissionGroupsPage, { global: { stubs } })
  await flushPromises()
  return w
}

describe('pages/teams/[slug]/permission-groups.vue', () => {
  beforeEach(() => {
    state.canGroups = true
    state.canGrant = true
    getPermissionGroups.mockReset()
    getPermissionGroups.mockResolvedValue({ data: [{ id: 1, name: '加盟担当', targetRole: 'MEMBER', permissions: [] }] })
  })

  it('TPG-01: 許可されていなければ出さず、API も呼ばない', async () => {
    state.canGroups = false
    const w = await mountPage()
    expect(w.find('[data-testid="group-list"]').exists()).toBe(false)
    expect(w.find('[data-testid="permission-group-create"]').exists()).toBe(false)
    expect(getPermissionGroups).not.toHaveBeenCalled()
  })

  it('TPG-02: 一覧を取得して渡す', async () => {
    const w = await mountPage()
    expect(getPermissionGroups).toHaveBeenCalledWith('team-1')
    expect(w.find('[data-testid="group-list"]').text()).toContain('加盟担当')
  })

  it('TPG-03: 取得失敗はエラー表示し、再試行で取り直せる', async () => {
    getPermissionGroups.mockRejectedValueOnce(new Error('boom'))
    const w = await mountPage()
    expect(w.find('[data-testid="permission-groups-load-error"]').exists()).toBe(true)
    expect(w.find('[data-testid="group-list"]').exists()).toBe(false)
    await w.find('[data-testid="permission-groups-retry"]').trigger('click')
    await flushPromises()
    expect(w.find('[data-testid="group-list"]').exists()).toBe(true)
  })

  it('TPG-05: 作成ダイアログを開き、保存後に一覧を取り直す', async () => {
    const w = await mountPage()
    await w.find('[data-testid="permission-group-create"]').trigger('click')
    const form = w.find('[data-testid="group-form"]')
    expect(form.exists()).toBe(true)
    expect(form.attributes('data-group-id')).toBeUndefined()
    expect(form.attributes('data-team-id')).toBe('77')
    await w.find('[data-testid="group-form-saved"]').trigger('click')
    await flushPromises()
    expect(getPermissionGroups).toHaveBeenCalledTimes(2)
  })

  it('TPG-06: 編集ボタンでそのグループの編集ダイアログが開く', async () => {
    const w = await mountPage()
    await w.find('[data-testid="group-edit-1"]').trigger('click')
    expect(w.find('[data-testid="group-form"]').attributes('data-group-id')).toBe('1')
  })

  it('TPG-08: 付与できない操作者には理由を出し、ダイアログへ false を渡す', async () => {
    state.canGrant = false
    const w = await mountPage()
    expect(w.find('[data-testid="permission-groups-admin-only-notice"]').exists()).toBe(true)
    await w.find('[data-testid="permission-group-create"]').trigger('click')
    expect(w.find('[data-testid="group-form"]').attributes('data-can-grant')).toBe('false')
  })

  it('TPG-07: メンバー一覧（割当の入口）へのリンクがある', async () => {
    const w = await mountPage()
    expect(w.find('[data-testid="permission-groups-go-members"]').attributes('href')).toBe('/teams/team-1/members')
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
 * CMP-261011-0035 ALIC-2 — 権限グループ一覧は権限コードではなく i18n の訳で出す。
 * 一覧（OrgPermissionGroupList）は stub にせず本物を描画する。
 *
 *   AC-ALIC2-01 MANAGE_SCHEDULES・MANAGE_ORG_AFFILIATION・jobs.manage が ja で訳表示、コード文字列が DOM にない
 *   AC-ALIC2-03 「対象: メンバー」「対象: 副管理者」（en Member／Deputy admin）、未知のロールはコード
 *   AC-ALIC2-09 加盟操作の印・件数・編集ボタンは不変（回帰）
 */
describe('pages/teams/[slug]/permission-groups.vue — 権限名の表示（ALIC-2・一覧は本物）', () => {
  const CODES = ['MANAGE_SCHEDULES', 'MANAGE_ORG_AFFILIATION', 'jobs.manage']
  const realListStubs = { NuxtLink: stubs.NuxtLink, TeamPermissionGroupFormDialog: stubs.TeamPermissionGroupFormDialog }

  beforeEach(() => {
    state.canGroups = true
    state.canGrant = true
    getPermissionGroups.mockReset()
    getPermissionGroups.mockResolvedValue({ data: [
      { id: 1, name: '加盟担当', targetRole: 'MEMBER', permissions: CODES },
      { id: 2, name: '副管理の係', targetRole: 'DEPUTY_ADMIN', permissions: ['MANAGE_SCHEDULES'] },
      { id: 3, name: '未知ロールの係', targetRole: 'OWNER_X', permissions: [] },
    ] })
  })

  async function mountRealList(locale: 'ja' | 'en' = 'ja') {
    const w = await mountSuspended(TeamPermissionGroupsPage, { global: { stubs: realListStubs } })
    await flushPromises()
    await switchLocale(w, locale)
    return w
  }

  it('AC-ALIC2-01: 権限は ja の訳で表示し、権限コードの文字列は DOM に出さない', async () => {
    const w = await mountRealList()
    const text = w.text()
    for (const code of CODES) {
      const label = expectedPermissionLabel('ja', code)
      expect(label, `ja の teamAffiliations.permission_names に ${code} の訳が無い`).toBeTypeOf('string')
      expect(text).toContain(label)
      expect(text).not.toContain(code)
    }
  })

  it('AC-ALIC2-03: 対象ロールは「対象: メンバー」「対象: 副管理者」、未知はコードのまま', async () => {
    const w = await mountRealList()
    expect(w.find('[data-testid="group-target-role-1"]').text()).toBe(expectedTargetRoleLabel('ja', 'MEMBER'))
    expect(w.find('[data-testid="group-target-role-2"]').text()).toBe(expectedTargetRoleLabel('ja', 'DEPUTY_ADMIN'))
    expect(w.find('[data-testid="group-target-role-3"]').text()).toBe(expectedTargetRoleLabel('ja', 'OWNER_X'))
  })

  it('AC-ALIC2-03: en では Member／Deputy admin と英訳の権限名で出す', async () => {
    const w = await mountRealList('en')
    try {
      expect(w.find('[data-testid="group-target-role-1"]').text()).toBe(expectedTargetRoleLabel('en', 'MEMBER'))
      expect(w.find('[data-testid="group-target-role-2"]').text()).toBe(expectedTargetRoleLabel('en', 'DEPUTY_ADMIN'))
      const label = expectedPermissionLabel('en', 'MANAGE_SCHEDULES')
      expect(label, 'en の permission_names.MANAGE_SCHEDULES が無い').toBeTypeOf('string')
      expect(w.text()).toContain(label)
      expect(w.text()).not.toContain('MANAGE_SCHEDULES')
    }
    finally {
      await switchLocale(w, 'ja')
    }
  })

  it('AC-ALIC2-09（回帰）: 加盟操作の印・件数・編集ボタンは変わらない', async () => {
    const w = await mountRealList()
    expect(w.find('[data-testid="group-affiliation-mark-1"]').exists()).toBe(true)
    expect(w.find('[data-testid="group-affiliation-mark-2"]').exists()).toBe(false)
    expect(w.find('[data-testid="group-count-1"]').text()).toContain('3')
    expect(w.find('[data-testid="group-count-2"]').text()).toContain('1')
    expect(w.find('[data-testid="group-edit-1"]').exists()).toBe(true)
  })
})
