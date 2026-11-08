// @vitest-environment happy-dom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import { defineComponent, h, nextTick, reactive, ref } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import OrganizationShell from '../organizations/[slug].vue'
import TeamShell from './[slug].vue'
import type { SlugResolveResponse } from '~/types/slug'
const mocks = vi.hoisted(() => ({
  route: { current: {} as Record<string, unknown> },
  getDetail: vi.fn(),
  resolve: vi.fn(),
  navigate: vi.fn(),
  error: vi.fn(),
}))
mockNuxtImport('useI18n', () => () => ({ t: (key: string) => key }))
vi.mock('~/components/OrganizationSidebar.vue', () => ({ default: { template: '<div />' } }))
vi.mock('~/components/TeamSidebar.vue', () => ({ default: { template: '<div />' } }))
mockNuxtImport('useRoute', () => () => mocks.route.current)
mockNuxtImport('navigateTo', () => mocks.navigate)
mockNuxtImport('useSlugRedirect', () => () => ({ resolveSlug: mocks.resolve }))
mockNuxtImport('useTeamApi', () => () => ({ getTeam: mocks.getDetail }))
mockNuxtImport('useOrganizationApi', () => () => ({
  getOrganization: mocks.getDetail,
  getTeamsInOrg: async () => ({ data: [] }),
  getPermissionGroups: async () => ({ data: [] }),
}))
mockNuxtImport('useErrorHandler', () => () => ({ handleApiError: mocks.error }))
mockNuxtImport('useNotification', () => () => ({ success: vi.fn(), error: vi.fn() }))
mockNuxtImport('useScopeLabels', () => () => ({ templateLabel: {}, visibilityLabel: {} }))
mockNuxtImport('useRoleAccess', () => () => ({
  roleName: ref('MEMBER'),
  roleResolved: ref(true),
  roleLoadFailed: ref(false),
  permissions: ref([]),
  loading: ref(false),
  isAdmin: ref(false),
  isScopeAdmin: ref(false),
  canGrantAdminOnlyPermissions: ref(false),
  isAdminOrDeputy: ref(false),
  loadPermissions: async () => ({ ok: true }),
}))
mockNuxtImport('useDashboardWidgetVisibility', () => () => ({
  settings: ref([]),
  fetch: async () => {},
}))
mockNuxtImport('useScopeFeatureVisibility', () => () => ({
  error: ref(null),
  isVisible: () => true,
}))
mockNuxtImport('useScopeFollowWiring', () => () => ({
  followStatus: ref('NONE'),
  followLoading: ref(false),
  followPermissionSyncError: ref(false),
  showCancelSupporterConfirm: ref(false),
  fetchFollowStatus: async () => {},
}))
mockNuxtImport('useJoinRequestSelfStatus', () => () => ({
  joinRequestStatus: ref('NONE'),
  joinRequestLoading: ref(false),
}))
mockNuxtImport('useOrgHierarchy', () => () => ({
  ancestors: ref([]),
  children: ref([]),
  loading: ref(false),
  childrenHasNext: ref(false),
  fetchAncestors: async () => {},
  fetchChildren: async () => {},
}))
mockNuxtImport('useModuleApi', () => () => ({ getTeamModules: async () => ({ data: [] }) }))
function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason: unknown) => void
  const promise = new Promise<T>((res, rej) => {
    resolve = res
    reject = rej
  })
  return { promise, resolve, reject }
}
const Header = defineComponent({
  props: ['displayName', 'org'],
  setup: (props) => () => h('div', props.displayName ?? props.org?.basicInfo?.name),
})
const Shell = defineComponent({
  setup:
    (_, { slots }) =>
    () =>
      h('section', [slots.header?.(), slots.default?.()]),
})
function setRoute(scope: string, slug: string) {
  Object.assign(mocks.route.current, {
    path: `/${scope}/${slug}/info`,
    params: { slug },
    query: { view: 'detail' },
    hash: '#overview',
  })
}
async function settle() {
  await flushPromises()
  await nextTick()
}
async function mountShell(scope: 'organizations' | 'teams') {
  return mount(scope === 'organizations' ? OrganizationShell : TeamShell, {
    global: {
      stubs: {
        ScopePageShell: Shell,
        OrgPageHeader: Header,
        TeamPageHeader: Header,
        NuxtPage: true,
        NuxtLink: true,
        LoadingBounce: true,
        Button: true,
        OrganizationSidebar: true,
        TeamSidebar: true,
        Dialog: true,
        MemberPermissionSetupDialog: true,
      },
    },
  })
}
beforeEach(() => {
  vi.clearAllMocks()
  mocks.route.current = reactive({})
  mocks.getDetail.mockImplementation(async (slug: string) => ({
    data: { slug, id: slug, numericId: 1, basicInfo: { name: `成功:${slug}` } },
  }))
  mocks.resolve.mockResolvedValue({ status: 'CURRENT' })
})
for (const scope of ['organizations', 'teams'] as const) {
  describe(`${scope} 実ページのMOVEDと寿命（CMP-261006-0632）`, () => {
    for (const outcome of ['MOVED', 'CURRENT', 'NOT_FOUND', 'missing', 'reject'] as const) {
      it(`M1/M3: 旧A破棄→B→新A成功後の旧${outcome}は遷移・エラー再表示しない`, async () => {
        const oldResolve = deferred<SlugResolveResponse>()
        setRoute(scope, 'a')
        mocks.getDetail.mockRejectedValueOnce({ response: { status: 404 } })
        mocks.resolve.mockReturnValueOnce(oldResolve.promise)
        const oldA = await mountShell(scope)
        await settle()
        expect(mocks.resolve).toHaveBeenCalledTimes(1)
        oldA.unmount()
        setRoute(scope, 'b')
        const b = await mountShell(scope)
        await settle()
        expect(b.text()).toContain('成功:b')
        b.unmount()
        setRoute(scope, 'a')
        const newA = await mountShell(scope)
        await settle()
        expect(newA.text()).toContain('成功:a')
        const errorsBefore = mocks.error.mock.calls.length
        if (outcome === 'reject') oldResolve.reject(new Error('offline'))
        else
          oldResolve.resolve(
            outcome === 'missing'
              ? { status: 'MOVED' }
              : { status: outcome, canonicalSlug: 'canonical-a' },
          )
        await settle()
        expect(mocks.navigate).not.toHaveBeenCalled()
        expect(mocks.error).toHaveBeenCalledTimes(errorsBefore)
        expect(newA.text()).toContain('成功:a')
        newA.unmount()
      })
    }
    it('M2: 生存MOVEDはサブパス/query/hashと301 replaceを保ち1回遷移する', async () => {
      setRoute(scope, 'old')
      mocks.getDetail.mockRejectedValueOnce({ response: { status: 404 } })
      mocks.resolve.mockResolvedValueOnce({ status: 'MOVED', canonicalSlug: 'new' })
      const wrapper = await mountShell(scope)
      await settle()
      expect(mocks.navigate).toHaveBeenCalledExactlyOnceWith(
        { path: `/${scope}/new/info`, query: { view: 'detail' }, hash: '#overview' },
        { redirectCode: 301, replace: true },
      )
      wrapper.unmount()
    })
    it('M4: 同一インスタンスA→B→A成功後の旧MOVEDは遷移しない', async () => {
      const oldResolve = deferred<SlugResolveResponse>()
      setRoute(scope, 'a')
      mocks.getDetail.mockRejectedValueOnce({ response: { status: 404 } })
      mocks.resolve.mockReturnValueOnce(oldResolve.promise)
      const wrapper = await mountShell(scope)
      await settle()
      setRoute(scope, 'b')
      await settle()
      setRoute(scope, 'a')
      await settle()
      expect(wrapper.text()).toContain('成功:a')
      oldResolve.resolve({ status: 'MOVED', canonicalSlug: 'obsolete' })
      await settle()
      expect(mocks.navigate).not.toHaveBeenCalled()
      expect(wrapper.text()).toContain('成功:a')
      wrapper.unmount()
    })
  })
}
it('M4: チーム破棄時に15秒watchdogを解除する', async () => {
  setRoute('teams', 'pending')
  const pending = deferred<never>()
  mocks.getDetail.mockReturnValueOnce(pending.promise)
  const arm = vi.spyOn(globalThis, 'setTimeout')
  const wrapper = await mountShell('teams')
  const timerIndex = arm.mock.calls.findIndex(([, delay]) => delay === 15_000)
  expect(timerIndex).toBeGreaterThanOrEqual(0)
  const watchdog = arm.mock.results[timerIndex]!.value
  const clear = vi.spyOn(globalThis, 'clearTimeout')
  wrapper.unmount()
  expect(clear).toHaveBeenCalledWith(watchdog)
  clear.mockRestore()
  arm.mockRestore()
  pending.reject({ response: { status: 403 } })
  await settle()
})
