import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { computed } from 'vue'
import TeamPermissionGroupsPage from '~/pages/teams/[slug]/permission-groups.vue'

/**
 * F01.2.1 8-D — /teams/{slug}/permission-groups（チーム側の権限グループ。§3.2）。
 *
 *   TPG-01 チーム ADMIN・管理レンズ ON でなければ画面を出さず、API も呼ばない（AC-K03）
 *   TPG-02 許可されていれば一覧を取得して OrgPermissionGroupList に渡す
 *   TPG-03 取得失敗は空に見せずエラー表示し、再試行できる
 *   TPG-04 組織側の OrgPermissionGroupList を流用している（一覧コンポーネントに groups が渡る）
 */

const state = { canGroups: true }
const getPermissionGroups = vi.fn()

vi.mock('~/composables/useTeamShellContext', () => ({
  useTeamShellContext: () => ({
    canManagePermissionGroups: computed(() => state.canGroups),
    displayName: computed(() => 'チームA'),
  }),
}))
vi.mock('~/composables/team/useTeamMembers', () => ({
  useTeamMembers: () => ({ getPermissionGroups }),
}))
mockNuxtImport('useRoute', () => () => ({ params: { slug: 'team-1' }, query: {}, path: '/teams/team-1/permission-groups' }))

const stubs = {
  OrgPermissionGroupList: {
    props: ['groups'],
    template: '<ul data-testid="group-list"><li v-for="g in groups" :key="g.id">{{ g.name }}</li></ul>',
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
    getPermissionGroups.mockReset()
    getPermissionGroups.mockResolvedValue({ data: [{ id: 1, name: '加盟担当', targetRole: 'MEMBER', permissions: [] }] })
  })

  it('TPG-01: 許可されていなければ出さず、API も呼ばない', async () => {
    state.canGroups = false
    const w = await mountPage()
    expect(w.find('[data-testid="group-list"]').exists()).toBe(false)
    expect(getPermissionGroups).not.toHaveBeenCalled()
  })

  it('TPG-02/04: 一覧を取得して渡す', async () => {
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
})
