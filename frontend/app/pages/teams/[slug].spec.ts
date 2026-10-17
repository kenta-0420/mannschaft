import { beforeAll, describe, it, expect, vi } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { ref } from 'vue'
import TeamShell from './[slug].vue'

const { getTeam, getTeamModules, fetchVisibility } = vi.hoisted(() => ({
  getTeam: vi.fn().mockResolvedValue({ data: { slug: 'clinic', timezone: 'Europe/Berlin' } }),
  getTeamModules: vi.fn().mockResolvedValue({ data: [] }),
  fetchVisibility: vi.fn().mockResolvedValue(undefined),
}))
mockNuxtImport('useRoute', () => () => ({ path: '/teams/clinic/admin/reservations', params: { slug: 'clinic' } }))
mockNuxtImport('useTeamApi', () => () => ({ getTeam }))
mockNuxtImport('useModuleApi', () => () => ({ getTeamModules }))
mockNuxtImport('useScopeLabels', () => () => ({ templateLabel: {}, visibilityLabel: {} }))
mockNuxtImport('useRoleAccess', () => () => ({
  roleName: ref('ADMIN'), loading: ref(false), isAdmin: ref(true), isAdminOrDeputy: ref(true),
  loadPermissions: vi.fn().mockResolvedValue({ ok: true }),
}))
mockNuxtImport('useDashboardWidgetVisibility', () => () => ({
  settings: ref([]), fetch: fetchVisibility,
}))
mockNuxtImport('useScopeFollowWiring', () => () => ({
  followStatus: ref('NONE'), followLoading: ref(false), followPermissionSyncError: ref(false),
  showCancelSupporterConfirm: ref(false), fetchFollowStatus: vi.fn(),
}))
mockNuxtImport('useJoinRequestSelfStatus', () => () => ({
  joinRequestStatus: ref('NONE'), joinRequestLoading: ref(false),
}))

// 実際の親ルートをmountし、末尾reservationsがshellへ誤分類されないことを検証する。
async function mountShell() {
  return mountSuspended(TeamShell, {
    global: { stubs: { NuxtPage: { template: '<div data-testid="bare-child" />' } } },
  })
}

beforeAll(async () => {
  const wrapper = await mountShell()
  wrapper.unmount()
  vi.clearAllMocks()
})

describe('チーム管理コンソールのshell境界', () => {
  it('admin/reservationsでは子ページだけを表示しshell用取得を行わない', async () => {
    const wrapper = await mountShell()

    expect(wrapper.find('[data-testid="bare-child"]').exists()).toBe(true)
    expect(getTeam).not.toHaveBeenCalled()
    expect(getTeamModules).not.toHaveBeenCalled()
    expect(fetchVisibility).not.toHaveBeenCalled()
    wrapper.unmount()
  })
})
