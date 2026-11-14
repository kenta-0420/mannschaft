import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises } from '@vue/test-utils'
import { markRaw } from 'vue'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import { useFeatureFlagStore } from '~/stores/featureFlags'

const { apiMock, policyMock } = vi.hoisted(() => ({ apiMock: vi.fn(), policyMock: vi.fn() }))
vi.mock('~/composables/useApi', () => ({ useApi: () => (url: string, ...args: unknown[]) => url.endsWith('/feature-display-policies') ? policyMock(url, ...args) : apiMock(url, ...args) }))
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
    clearNuxtState('scope-feature-display-policies')
    policyMock.mockReset().mockResolvedValue({ data: { features: [] } })
    apiMock.mockReset()
    apiMock.mockImplementation((url: string) => Promise.resolve(
      url.endsWith('/me/permissions') ? permissions() : { data: [] },
    ))
  })

  it.each(['MEMBER', 'SUPPORTER', 'ADMIN'])('%sの一般項目は既存権限と表示policyのANDを維持する', async role => {
    apiMock.mockImplementation((url: string) => Promise.resolve(url.endsWith('/me/permissions') ? permissions(role) : { data: [] }))
    policyMock.mockResolvedValue({ data: { features: [{ featureKey: 'duties', memberVisible: false, supporterVisible: true }] } })
    const wrapper = await mountSuspended(BaseSidebar, { props: { scopeType: 'team', scopeId: 'team-policy', categories: [{ key: 'home', labelKey: 'common.menu', icon: '', items: [{ labelKey: 'common.menu', icon: '', path: 'duties', moduleSlug: null, requiredRole: 'MEMBER' }] }] } })
    await flushPromises()
    expect(wrapper.find('a[href="/teams/team-policy/duties"]').exists()).toBe(role === 'ADMIN')
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

/** CMP-260909-0545: 実Sidebar・実権限composableを通し、API応答境界だけを差し替える。 */
describe('組織Sidebarのシフト予算入口', () => {
  const href = 'a[href="/admin/shift-budget/dashboard"]'
  const mounted: Array<{ unmount: () => void }> = []

  function budgetPermissions(roleName: string, granted: readonly string[] = ['BUDGET_VIEW']) {
    return { data: { roleName, permissions: [...granted] } }
  }

  function deferredPermissions() {
    let resolve!: (value: ReturnType<typeof budgetPermissions>) => void
    const promise = new Promise<ReturnType<typeof budgetPermissions>>(done => { resolve = done })
    return { promise, resolve }
  }

  async function mountBudgetSidebar(memberLensActive = false) {
    const wrapper = await mountSuspended(OrganizationSidebar, {
      props: { orgId: 'org-a', memberLensActive },
    })
    mounted.push(wrapper)
    await flushPromises()
    return wrapper
  }

  beforeEach(() => {
    localStorage.clear()
    clearNuxtState('scope-feature-display-policies')
    policyMock.mockReset().mockResolvedValue({ data: { features: [] } })
    apiMock.mockReset().mockImplementation((url: string) => Promise.resolve(
      url.endsWith('/me/permissions') ? budgetPermissions('ADMIN') : { data: [] },
    ))
    useFeatureFlagStore().flags = { FEATURE_SHIFT_ENABLED: true }
  })

  afterEach(() => {
    for (const wrapper of mounted.splice(0)) wrapper.unmount()
    useFeatureFlagStore().flags = {}
  })

  it.each(['ADMIN', 'DEPUTY_ADMIN', 'MEMBER', 'SYSTEM_ADMIN'])('%sは通常表示で読取可能なシフト予算へ一意に到達できる', async role => {
    apiMock.mockImplementation((url: string) => Promise.resolve(url.endsWith('/me/permissions')
      ? budgetPermissions(role, role === 'SYSTEM_ADMIN' ? [] : ['BUDGET_VIEW'])
      : { data: [] }))
    const wrapper = await mountBudgetSidebar()

    // budgetモジュールは無効（一覧空）でも、既存読取APIにない追加制約を課さない。
    expect(wrapper.findAll(href)).toHaveLength(1)
    await wrapper.get('button:has(i.pi-database)').trigger('click')
    expect(wrapper.get(href).isVisible()).toBe(true)
    expect(apiMock.mock.calls.filter(([url]) => String(url).endsWith('/me/permissions'))).toHaveLength(1)
    expect(apiMock.mock.calls.filter(([url]) => String(url).endsWith('/modules'))).toHaveLength(role === 'SYSTEM_ADMIN' ? 0 : 1)
  })

  it.each([
    ['ADMIN', []],
    ['DEPUTY_ADMIN', ['BUDGET_ADMIN']],
    ['MEMBER', ['BUDGET_ADMIN']],
  ] as const)('%sでもBUDGET_VIEWがなければ読取入口を出さない', async (role, granted) => {
    apiMock.mockImplementation((url: string) => Promise.resolve(url.endsWith('/me/permissions')
      ? budgetPermissions(role, granted) : { data: [] }))
    const wrapper = await mountBudgetSidebar()

    expect(wrapper.find(href).exists()).toBe(false)
  })

  it('権限取得が正常に未所属を返した場合は入口もナビも表示しない', async () => {
    apiMock.mockImplementation((url: string) => Promise.resolve(url.endsWith('/me/permissions')
      ? { data: { roleName: null, permissions: [] } } : { data: [] }))
    const wrapper = await mountBudgetSidebar()

    expect(wrapper.find(href).exists()).toBe(false)
    expect(wrapper.find('nav').exists()).toBe(false)
    expect(wrapper.find('[role="status"]').exists()).toBe(false)
    expect(wrapper.findComponent({ name: 'DashboardErrorState' }).exists()).toBe(false)
  })

  it.each(['ADMIN', 'SYSTEM_ADMIN'])('%sもSHIFTフラグ未取得・OFFでは入口がなくONに追従する', async role => {
    apiMock.mockImplementation((url: string) => Promise.resolve(url.endsWith('/me/permissions')
      ? budgetPermissions(role, role === 'SYSTEM_ADMIN' ? [] : ['BUDGET_VIEW']) : { data: [] }))
    const flags = useFeatureFlagStore()
    flags.flags = {}
    const wrapper = await mountBudgetSidebar()
    expect(wrapper.find(href).exists()).toBe(false)

    flags.flags = { FEATURE_SHIFT_ENABLED: true }
    await flushPromises()
    expect(wrapper.findAll(href)).toHaveLength(1)

    flags.flags = { FEATURE_SHIFT_ENABLED: false }
    await flushPromises()
    expect(wrapper.find(href).exists()).toBe(false)
  })

  it.each(['ADMIN', 'SYSTEM_ADMIN'])('%sのメンバープレビューから管理画面へ抜けず通常表示に戻すと入口が復元する', async role => {
    apiMock.mockImplementation((url: string) => Promise.resolve(url.endsWith('/me/permissions')
      ? budgetPermissions(role, role === 'SYSTEM_ADMIN' ? [] : ['BUDGET_VIEW']) : { data: [] }))
    const wrapper = await mountBudgetSidebar(true)
    expect(wrapper.find(href).exists()).toBe(false)

    await wrapper.setProps({ memberLensActive: false })
    await flushPromises()
    expect(wrapper.findAll(href)).toHaveLength(1)

    await wrapper.setProps({ memberLensActive: true })
    await flushPromises()
    expect(wrapper.find(href).exists()).toBe(false)
  })

  it('権限取得中・取得失敗では入口を出さず正規の再試行で復元する', async () => {
    const pending = deferredPermissions()
    apiMock.mockImplementation((url: string) => url.endsWith('/me/permissions')
      ? pending.promise.then(() => { throw new Error('permissions unavailable') })
      : Promise.resolve({ data: [] }))
    const wrapper = await mountBudgetSidebar()
    expect(wrapper.find('[role="status"]').exists()).toBe(true)
    expect(wrapper.find(href).exists()).toBe(false)

    pending.resolve(budgetPermissions('ADMIN'))
    await flushPromises()
    expect(wrapper.findComponent({ name: 'DashboardErrorState' }).exists()).toBe(true)
    expect(wrapper.find(href).exists()).toBe(false)

    apiMock.mockImplementation((url: string) => Promise.resolve(url.endsWith('/me/permissions')
      ? budgetPermissions('ADMIN') : { data: [] }))
    wrapper.findComponent({ name: 'DashboardErrorState' }).vm.$emit('retry')
    await flushPromises()
    expect(wrapper.findAll(href)).toHaveLength(1)
  })

  it('組織Aの閲覧許可を組織Bへ持ち越さずBの読取結果で表示を決める', async () => {
    const pendingB = deferredPermissions()
    apiMock.mockImplementation((url: string) => {
      if (url === '/api/v1/organizations/org-b/me/permissions') return pendingB.promise
      return Promise.resolve(url.endsWith('/me/permissions') ? budgetPermissions('ADMIN') : { data: [] })
    })
    const wrapper = await mountBudgetSidebar()
    expect(wrapper.findAll(href)).toHaveLength(1)

    await wrapper.setProps({ orgId: 'org-b' })
    await flushPromises()
    expect(wrapper.find(href).exists()).toBe(false)
    expect(apiMock).toHaveBeenCalledWith('/api/v1/organizations/org-b/me/permissions')
    pendingB.resolve(budgetPermissions('MEMBER', []))
    await flushPromises()
    expect(wrapper.find(href).exists()).toBe(false)
  })

  it('組織Aの遅い拒否応答で読取可能な組織Bの入口を消さない', async () => {
    const pendingA = deferredPermissions()
    apiMock.mockImplementation((url: string) => {
      if (url === '/api/v1/organizations/org-a/me/permissions') return pendingA.promise
      return Promise.resolve(url.endsWith('/me/permissions') ? budgetPermissions('MEMBER') : { data: [] })
    })
    const wrapper = await mountBudgetSidebar()
    await wrapper.setProps({ orgId: 'org-b' })
    await flushPromises()
    expect(wrapper.findAll(href)).toHaveLength(1)

    pendingA.resolve(budgetPermissions('MEMBER', []))
    await flushPromises()
    expect(wrapper.findAll(href)).toHaveLength(1)
  })

  it('組織Aの遅い許可応答で権限のない組織Bへ入口を復活させない', async () => {
    const pendingA = deferredPermissions()
    apiMock.mockImplementation((url: string) => {
      if (url === '/api/v1/organizations/org-a/me/permissions') return pendingA.promise
      return Promise.resolve(url.endsWith('/me/permissions') ? budgetPermissions('MEMBER', []) : { data: [] })
    })
    const wrapper = await mountBudgetSidebar()
    await wrapper.setProps({ orgId: 'org-b' })
    await flushPromises()
    expect(wrapper.find(href).exists()).toBe(false)

    pendingA.resolve(budgetPermissions('ADMIN'))
    await flushPromises()
    expect(wrapper.find(href).exists()).toBe(false)
  })
})
