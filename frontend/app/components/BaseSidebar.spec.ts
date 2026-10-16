import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises } from '@vue/test-utils'
import { markRaw } from 'vue'
import { mountSuspended } from '@nuxt/test-utils/runtime'

const apiMock = vi.hoisted(() => vi.fn())
vi.mock('~/composables/useApi', () => ({ useApi: () => apiMock }))
const BaseSidebar = (await import('./BaseSidebar.vue')).default
const TeamSidebar = (await import('./TeamSidebar.vue')).default
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
})
