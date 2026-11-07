import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises } from '@vue/test-utils'
import { ref } from 'vue'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'

const mocks = vi.hoisted(() => ({
  getScope: vi.fn(), getScopedThread: vi.fn(), handleApiError: vi.fn(),
  route: { params: { slug: 'scope-slug' }, query: { threadId: '23' } },
}))
mockNuxtImport('useRoute', () => () => mocks.route)
mockNuxtImport('useRoleAccess', () => () => ({
  isAdminOrDeputy: ref(false), isMember: ref(true), loadPermissions: vi.fn().mockResolvedValue(undefined),
}))
mockNuxtImport('useBulletinApi', () => () => ({ getScopedThread: mocks.getScopedThread }))
mockNuxtImport('useTeamApi', () => () => ({ getTeam: mocks.getScope }))
mockNuxtImport('useOrganizationApi', () => () => ({ getOrganization: mocks.getScope }))
mockNuxtImport('useErrorHandler', () => () => ({ handleApiError: mocks.handleApiError }))

const cases = [
  { type: 'TEAM', path: 'teams', load: () => import('~/pages/teams/[slug]/bulletin.vue') },
  { type: 'ORGANIZATION', path: 'organizations', load: () => import('~/pages/organizations/[slug]/bulletin.vue') },
] as const
const stubs = {
  PageHeader: true, PageLoading: true, DashboardErrorState: true,
  BulletinThreadDetail: true, BulletinThreadList: true, BulletinArchiveView: true, BulletinThreadForm: true,
}

beforeEach(() => {
  vi.clearAllMocks()
  mocks.getScope.mockResolvedValue({ data: { id: 'scope-slug', slug: 'scope-slug', numericId: 21 } })
})

describe.each(cases)('PREVIEW-08: $type 元掲示板の実所有scope照合', ({ type, path, load }) => {
  async function open() {
    const page = await load()
    const wrapper = await mountSuspended(page.default, { global: { stubs } })
    await flushPromises()
    return wrapper
  }

  it('slugと数値IDが異なっていても数値scopeで取得し本文詳細を開く', async () => {
    mocks.getScopedThread.mockResolvedValue({ data: { id: 23, scopeType: type, scopeId: 21 } })
    const wrapper = await open()
    expect(mocks.getScopedThread).toHaveBeenCalledWith(path, '21', 23)
    expect(wrapper.findComponent({ name: 'BulletinThreadDetail' }).props('threadId')).toBe(23)
    expect(wrapper.findComponent({ name: 'DashboardErrorState' }).exists()).toBe(false)
    expect(mocks.handleApiError).not.toHaveBeenCalled()
  })

  it.each([
    { id: 23, scopeType: type, scopeId: 22 },
    { id: 23, scopeType: type === 'TEAM' ? 'ORGANIZATION' : 'TEAM', scopeId: 21 },
    { id: 24, scopeType: type, scopeId: 21 },
  ])('別scope・種別・threadの応答を本文として表示しない: %j', async (thread) => {
    mocks.getScopedThread.mockResolvedValue({ data: thread })
    const wrapper = await open()
    expect(wrapper.findComponent({ name: 'BulletinThreadDetail' }).exists()).toBe(false)
    expect(wrapper.findComponent({ name: 'DashboardErrorState' }).exists()).toBe(true)
    expect(mocks.handleApiError).toHaveBeenCalledOnce()
  })

  it.each([undefined, null, 0, -1, 1.5, Number.MAX_SAFE_INTEGER + 1])('内部IDが不正(%s)なら取得せずエラーを表示する', async (numericId) => {
    mocks.getScope.mockResolvedValue({ data: { id: 'scope-slug', numericId } })
    const wrapper = await open()
    expect(mocks.getScopedThread).not.toHaveBeenCalled()
    expect(wrapper.findComponent({ name: 'BulletinThreadDetail' }).exists()).toBe(false)
    expect(wrapper.findComponent({ name: 'DashboardErrorState' }).exists()).toBe(true)
    expect(mocks.handleApiError).toHaveBeenCalledOnce()
  })
})
