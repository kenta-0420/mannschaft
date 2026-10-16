import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises } from '@vue/test-utils'
import { markRaw } from 'vue'
import { mountSuspended } from '@nuxt/test-utils/runtime'

const apiMock = vi.hoisted(() => vi.fn())
vi.mock('~/composables/useApi', () => ({ useApi: () => apiMock }))
const BaseSidebar = (await import('./BaseSidebar.vue')).default
const TeamSidebar = (await import('./TeamSidebar.vue')).default
const OrganizationSidebar = (await import('./OrganizationSidebar.vue')).default
const ScopePageShell = (await import('./scope/ScopePageShell.vue')).default

const categories = [{ key: 'home', labelKey: 'common.menu', icon: 'pi pi-home', items: [
  { labelKey: 'common.menu', icon: 'pi pi-home', path: 'admin', moduleSlug: null, requiredRole: 'ADMIN' as const },
] }]

function permissions(roleName = 'ADMIN') {
  return { data: { roleName, permissions: [] } }
}

async function mountSidebar() {
  return await mountSuspended(BaseSidebar, {
    props: { scopeType: 'team', scopeId: 'team-a', categories },
  })
}

describe('BaseSidebar の取得状態', () => {
  beforeEach(() => {
    localStorage.clear()
    apiMock.mockReset()
    apiMock.mockImplementation((url: string) => Promise.resolve(
      url.endsWith('/me/permissions') ? permissions() : { data: [] },
    ))
  })

  it('実際の権限composableでADMIN応答を受けるとメニューリンクを描画する', async () => {
    const wrapper = await mountSidebar()
    await flushPromises()

    expect(wrapper.find('nav').exists()).toBe(true)
    expect(wrapper.find('a[href="/teams/team-a/admin"]').exists()).toBe(true)
    expect(apiMock).toHaveBeenCalledWith('/api/v1/teams/team-a/me/permissions')
  })

  it('未所属のSYSTEM_ADMINは所属限定モジュールAPIを取得せずメニューリンクを表示する', async () => {
    apiMock.mockImplementation((url: string) => url.endsWith('/me/permissions')
      ? Promise.resolve(permissions('SYSTEM_ADMIN'))
      : Promise.reject(Object.assign(new Error('membership required'), { statusCode: 403 })))
    const wrapper = await mountSidebar()
    await flushPromises()

    expect(wrapper.find('a[href="/teams/team-a/admin"]').exists()).toBe(true)
    expect(wrapper.get('a[href="/teams/team-a/admin"]').isVisible()).toBe(true)
    expect(wrapper.findComponent({ name: 'DashboardErrorState' }).exists()).toBe(false)
    expect(apiMock).toHaveBeenCalledWith('/api/v1/teams/team-a/me/permissions')
    expect(apiMock).not.toHaveBeenCalledWith('/api/v1/teams/team-a/modules')
  })

  it('SYSTEM_ADMINもメンバーレンズ中のモジュール取得失敗をエラーとして表示する', async () => {
    apiMock.mockImplementation((url: string) => url.endsWith('/me/permissions')
      ? Promise.resolve(permissions('SYSTEM_ADMIN'))
      : Promise.reject(Object.assign(new Error('membership required'), { statusCode: 403 })))
    const wrapper = await mountSuspended(BaseSidebar, {
      props: { scopeType: 'team', scopeId: 'team-a', categories, memberLensActive: true },
    })
    await flushPromises()

    expect(wrapper.findComponent({ name: 'DashboardErrorState' }).exists()).toBe(true)
    expect(wrapper.find('nav').exists()).toBe(false)
    expect(apiMock).toHaveBeenCalledWith('/api/v1/teams/team-a/modules')
  })

  it('Drawerを開いたとき実TeamSidebarが権限を取得しリンクを描画する', async () => {
    const wrapper = await mountSuspended(ScopePageShell, {
      props: { tabs: [], activeTab: 'dashboard', sidebar: markRaw(TeamSidebar), sidebarProps: { teamId: 'team-a' } },
      attachTo: document.body,
    })
    await wrapper.get('[data-testid="scope-sidebar-toggle"]').trigger('click')
    await flushPromises()

    expect(document.querySelector('.p-drawer-content nav')).not.toBeNull()
    expect(document.querySelector('.p-drawer-content a[href="/teams/team-a/admin"]')).not.toBeNull()
  })

  it('取得中は空白にせず読み込み状態を示す', async () => {
    apiMock.mockImplementation(() => new Promise(() => {}))
    const wrapper = await mountSidebar()

    expect(wrapper.find('[role="status"]').exists()).toBe(true)
    expect(wrapper.get('[role="status"]').attributes('aria-label')).toBe('Loading...')
    expect(wrapper.find('nav').exists()).toBe(false)
  })

  it('権限取得失敗は再試行可能なエラーとして表示し回復後にリンクを表示する', async () => {
    apiMock.mockImplementation((url: string) => url.endsWith('/me/permissions')
      ? Promise.reject(new Error('temporary failure'))
      : Promise.resolve({ data: [] }))
    const wrapper = await mountSidebar()
    await flushPromises()

    expect(wrapper.findComponent({ name: 'DashboardErrorState' }).exists()).toBe(true)
    apiMock.mockImplementation((url: string) => Promise.resolve(
      url.endsWith('/me/permissions') ? permissions() : { data: [] },
    ))
    wrapper.findComponent({ name: 'DashboardErrorState' }).vm.$emit('retry')
    await flushPromises()
    expect(wrapper.find('a[href="/teams/team-a/admin"]').exists()).toBe(true)
  })

  it('権限取得失敗は保留中のモジュール取得を待たずにエラー表示する', async () => {
    apiMock.mockImplementation((url: string) => url.endsWith('/me/permissions')
      ? Promise.reject(new Error('permissions failed'))
      : new Promise(() => {}))
    const wrapper = await mountSidebar()
    await flushPromises()

    expect(wrapper.findComponent({ name: 'DashboardErrorState' }).exists()).toBe(true)
    expect(wrapper.find('[role="status"]').exists()).toBe(false)
  })

  it('モジュール取得失敗もエラーとして表示し、再試行で権限とモジュールを両方取り直す', async () => {
    apiMock.mockImplementation((url: string) => url.endsWith('/me/permissions')
      ? Promise.resolve(permissions())
      : Promise.reject(new Error('modules unavailable')))
    const wrapper = await mountSidebar()
    await flushPromises()
    expect(wrapper.findComponent({ name: 'DashboardErrorState' }).exists()).toBe(true)
    expect(wrapper.find('nav').exists()).toBe(false)

    apiMock.mockClear()
    apiMock.mockImplementation((url: string) => Promise.resolve(
      url.endsWith('/me/permissions') ? permissions() : { data: [] },
    ))
    wrapper.findComponent({ name: 'DashboardErrorState' }).vm.$emit('retry')
    await flushPromises()

    expect(apiMock).toHaveBeenCalledWith('/api/v1/teams/team-a/me/permissions')
    expect(apiMock).toHaveBeenCalledWith('/api/v1/teams/team-a/modules')
    expect(wrapper.find('a[href="/teams/team-a/admin"]').exists()).toBe(true)
  })

  it.each(['MEMBER', 'SUPPORTER', 'GUEST'])('%sにはADMIN専用リンクを表示しない', async role => {
    apiMock.mockImplementation((url: string) => Promise.resolve(
      url.endsWith('/me/permissions') ? permissions(role) : { data: [] },
    ))
    const wrapper = await mountSidebar()
    await flushPromises()

    expect(wrapper.find('a[href="/teams/team-a/admin"]').exists()).toBe(false)
    if (role !== 'MEMBER') expect(wrapper.find('nav').exists()).toBe(false)
  })

  it.each(['members', 'invites', 'permission-groups', 'modules'])('組織の%s項目は実際の子ルートへのリンクを描画する', async path => {
    const wrapper = await mountSuspended(OrganizationSidebar, { props: { orgId: 'org-a' } })
    await flushPromises()

    expect(wrapper.find(`a[href="/organizations/org-a/${path}"]`).exists()).toBe(true)
  })

  it.each([
    ['MEMBER', ['members']],
    ['DEPUTY_ADMIN', ['members', 'invites']],
    ['ADMIN', ['members', 'invites', 'permission-groups', 'modules']],
  ] as const)('組織の子ルートリンクは%sの表示権限を維持する', async (role, expectedPaths) => {
    apiMock.mockImplementation((url: string) => Promise.resolve(
      url.endsWith('/me/permissions') ? permissions(role) : { data: [] },
    ))
    const wrapper = await mountSuspended(OrganizationSidebar, { props: { orgId: 'org-a' } })
    await flushPromises()

    for (const path of ['members', 'invites', 'permission-groups', 'modules']) {
      expect(wrapper.find(`a[href="/organizations/org-a/${path}"]`).exists()).toBe((expectedPaths as readonly string[]).includes(path))
    }
  })

  it('チーム切替後はモジュールを取り直し業者リンクも新しいチームへ向ける', async () => {
    apiMock.mockImplementation((url: string) => Promise.resolve(url.endsWith('/me/permissions')
      ? permissions()
      : { data: [{ moduleSlug: 'repair_longterm_plan', isEnabled: true }] }))
    const wrapper = await mountSuspended(TeamSidebar, { props: { teamId: 'team-a' } })
    await flushPromises()
    await wrapper.get('button:has(i.pi-building)').trigger('click')
    expect(wrapper.get('a[href="/admin/vendors?scope=teams&scopeId=team-a"]').isVisible()).toBe(true)

    await wrapper.setProps({ teamId: 'team-b' })
    await flushPromises()
    await wrapper.get('button:has(i.pi-building)').trigger('click')

    expect(apiMock).toHaveBeenCalledWith('/api/v1/teams/team-b/modules')
    expect(wrapper.get('a[href="/admin/vendors?scope=teams&scopeId=team-b"]').isVisible()).toBe(true)
    expect(wrapper.find('a[href="/admin/vendors?scope=teams&scopeId=team-a"]').exists()).toBe(false)
  })

  it('組織切替後はモジュールを取り直し業者リンクも新しい組織へ向ける', async () => {
    apiMock.mockImplementation((url: string) => Promise.resolve(url.endsWith('/me/permissions')
      ? permissions()
      : { data: [{ moduleSlug: 'repair_longterm_plan', isEnabled: true }] }))
    const wrapper = await mountSuspended(OrganizationSidebar, { props: { orgId: 'org-a' } })
    await flushPromises()
    await wrapper.get('button:has(i.pi-building)').trigger('click')
    expect(wrapper.get('a[href="/admin/vendors?scope=organizations&scopeId=org-a"]').isVisible()).toBe(true)

    await wrapper.setProps({ orgId: 'org-b' })
    await flushPromises()
    await wrapper.get('button:has(i.pi-building)').trigger('click')

    expect(apiMock).toHaveBeenCalledWith('/api/v1/organizations/org-b/modules')
    expect(wrapper.get('a[href="/admin/vendors?scope=organizations&scopeId=org-b"]').isVisible()).toBe(true)
    expect(wrapper.find('a[href="/admin/vendors?scope=organizations&scopeId=org-a"]').exists()).toBe(false)
  })
})
