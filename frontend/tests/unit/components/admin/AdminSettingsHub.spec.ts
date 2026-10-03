import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises } from '@vue/test-utils'
import { computed, reactive } from 'vue'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import { useScopeStore } from '~/stores/useScopeStore'
import { useTeamStore } from '~/stores/useTeamStore'
import { useOrganizationStore } from '~/stores/useOrganizationStore'
import AdminSettingsHub from '~/components/admin/AdminSettingsHub.vue'
import TeamSettingsPage from '~/pages/teams/[slug]/admin/settings/index.vue'
import OrganizationSettingsPage from '~/pages/organizations/[slug]/admin/settings/index.vue'
import { TeamShellContextKey } from '~/composables/useTeamShellContext'
import { OrgShellContextKey } from '~/composables/useOrgShellContext'

// モックは通信境界のみ。ロール解決、所属一覧、scope同期、表示部品は本物を動かす。
let role: string | null = 'ADMIN'
let payment = true
let failPermissions = false
let failModules = false
let failMemberships = false
let pendingPermissions: Promise<void> | null = null
let pendingMemberships: Promise<void> | null = null
let teamMembershipIdentity: { id: number; slug: string } | null = { id: 12, slug: 'alpha' }
let organizationId = 7
const route = reactive({ path: '/teams/alpha/admin/settings', params: { slug: 'alpha' } })
const api = vi.fn(async (path: string) => {
  if (path.endsWith('/me/permissions')) {
    if (pendingPermissions) await pendingPermissions
    if (failPermissions) throw { statusCode: 503 }
    return { data: { roleName: role, permissions: [] } }
  }
  if (path.endsWith('/modules')) {
    if (failModules) throw { statusCode: 503 }
    return { data: payment ? [{ moduleSlug: 'payment', isEnabled: true }] : [] }
  }
  if (path === '/api/v1/me/teams') {
    if (pendingMemberships) await pendingMemberships
    if (failMemberships) throw { statusCode: 503 }
    return { data: teamMembershipIdentity ? [{ ...teamMembershipIdentity, name: 'チームA', role: 'ADMIN' }] : [] }
  }
  if (path === '/api/v1/me/organizations') {
    if (failMemberships) throw { statusCode: 503 }
    return { data: [{ id: organizationId, slug: 'beta', name: '組織B', role: 'ADMIN' }] }
  }
  throw new Error(`Unexpected API: ${path}`)
})
// useApi自体をmockすると循環内のORGがoriginalを捕捉するため、葉の通信境界のみ置換する。
vi.mock('ofetch', async (importOriginal) => {
  const actual = await importOriginal<typeof import('ofetch')>()
  return {
    ...actual,
    ofetch: new Proxy(actual.ofetch, {
      get(target, key, receiver) {
        return key === 'create' ? () => api : Reflect.get(target, key, receiver)
      },
    }),
  }
})
mockNuxtImport('useI18n', () => () => ({ t: (key: string) => key }))
mockNuxtImport('useRoute', () => () => route)

const teamProps = { scopeType: 'team' as const, slug: 'alpha' }
const orgProps = { scopeType: 'organization' as const, slug: 'beta' }

beforeAll(async () => {
  const warmup = await mountSuspended(AdminSettingsHub, { props: teamProps })
  await flushPromises()
  warmup.unmount()
})

beforeEach(() => {
  role = 'ADMIN'
  payment = true
  failPermissions = false
  failModules = false
  failMemberships = false
  pendingPermissions = null
  pendingMemberships = null
  teamMembershipIdentity = { id: 12, slug: 'alpha' }
  organizationId = 7
  route.path = '/teams/alpha/admin/settings'
  route.params.slug = 'alpha'
  api.mockClear()
  useScopeStore().clear()
  useTeamStore().clear()
  useOrganizationStore().clear()
})

describe('ADMIN設定ハブの既存導線と団体境界', () => {
  it.each([
    { type: 'team', slug: 'alpha', id: '12', page: TeamSettingsPage, key: TeamShellContextKey, field: 'team' },
    { type: 'organization', slug: 'beta', id: '7', page: OrganizationSettingsPage, key: OrgShellContextKey, field: 'org' },
  ])('AC2/3: 非shellの実wrapperは親metadata未取得でも本人所属で横断設定を確定する（$type）', async ({ type, slug, id, page, key, field }) => {
    route.path = `/${type === 'team' ? 'teams' : 'organizations'}/${slug}/admin/settings`
    route.params.slug = slug
    // /admin/settingsの親は非shell分岐で本体metadataを取得しない。提供されるnullを再現する。
    const wrapper = await mountSuspended(page, { global: { provide: { [key as symbol]: { [field]: computed(() => null) } } } })
    await flushPromises()
    await vi.waitFor(() => expect(wrapper.findComponent({ name: 'PageLoading' }).exists()).toBe(false))
    expect(wrapper.find('[data-testid="setting-line"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="setting-receipts"]').exists()).toBe(true)
    expect(useScopeStore().current).toMatchObject({ type, id })
    expect(api.mock.calls.filter(([path]) => path === `/api/v1/me/${type === 'team' ? 'teams' : 'organizations'}`)).toHaveLength(1)
    expect(api.mock.calls.some(([path]) => path === `/api/v1/${type === 'team' ? 'teams' : 'organizations'}/${slug}`)).toBe(false)
    wrapper.unmount()
  })

  it('AC2/3/7: TEAM既存設定とLINE/領収書を表示し、実scopeを引き継ぐ（取得はカード数に比例しない）', async () => {
    const wrapper = await mountSuspended(AdminSettingsHub, { props: teamProps })
    await flushPromises()
    const hrefs = wrapper.findAll('a[href]').map(link => link.attributes('href'))
    for (const path of ['settings/shift', 'settings/faq-settings', 'settings/public-settings', 'settings/care-overrides', 'settings/todo-status-labels', 'modules']) {
      expect(hrefs).toContain(`/teams/alpha/${path}`)
    }
    expect(hrefs).toContain('/admin/line-settings')
    expect(hrefs).toContain('/admin/receipt-settings')
    expect(useScopeStore().current).toMatchObject({ type: 'team', id: '12' })
    expect(wrapper.get('[data-testid="setting-care"]').text()).toBe('adminConsole.settingsHub.items.care')
    expect(api.mock.calls.map(([path]) => path).sort()).toEqual([
      '/api/v1/me/teams', '/api/v1/teams/alpha/me/permissions', '/api/v1/teams/alpha/modules',
    ].sort())
    wrapper.unmount()
  })

  it('AC2/5/6: ORG専用リンクと戻り先を表示し、TEAM設定・廃止予約・SYS税を掲載しない', async () => {
    route.path = '/organizations/beta/admin/settings'
    const wrapper = await mountSuspended(AdminSettingsHub, { props: orgProps })
    await flushPromises()
    const permissionIndex = api.mock.calls.findIndex(([path]) => path === '/api/v1/organizations/beta/me/permissions')
    expect(permissionIndex, 'ORG権限は現在slugの通信境界へ問い合わせる').toBeGreaterThanOrEqual(0)
    expect(await api.mock.results[permissionIndex]!.value, 'ORG fixtureの実応答はADMIN').toMatchObject({ data: { roleName: 'ADMIN' } })
    // load() の複数await完了を確認してから表示契約を評価し、未完了を権限拒否と混同しない。
    await vi.waitFor(() => expect(wrapper.findComponent({ name: 'PageLoading' }).exists()).toBe(false))
    expect(api.mock.calls.filter(([path]) => path === '/api/v1/me/organizations')).toHaveLength(1)
    const errorState = wrapper.findComponent({ name: 'DashboardErrorState' })
    expect(errorState.exists(), JSON.stringify({
      apiPaths: api.mock.calls.map(([path]) => path),
      error: errorState.exists() ? String(errorState.props('error')) : null,
      kind: errorState.exists() ? errorState.props('kind') : null,
    })).toBe(false)
    const hrefs = wrapper.findAll('a[href]').map(link => link.attributes('href'))
    for (const path of ['settings/faq-settings', 'settings/notification-credits', 'settings/public-settings', 'settings/todo-status-labels', 'modules']) {
      expect(hrefs).toContain(`/organizations/beta/${path}`)
    }
    expect(hrefs).toContain('/organizations/beta/admin')
    expect(hrefs.some(path => /shift|care-overrides|reservation|tax/.test(path ?? ''))).toBe(false)
    expect(useScopeStore().current).toMatchObject({ type: 'organization', id: '7' })
    wrapper.unmount()
  })

  it('AC2: 空モジュールは正常な空として扱い、LINEは残し領収書のみ非掲載', async () => {
    payment = false
    const wrapper = await mountSuspended(AdminSettingsHub, { props: teamProps })
    await flushPromises()
    expect(wrapper.find('[data-testid="setting-line"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="setting-receipts"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="settings-module-error"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('AC3: TEAMと同じ数値IDのORGでも本人所属の種別を確認し、旧scopeへ横断遷移しない', async () => {
    route.path = '/organizations/beta/admin/settings'
    organizationId = 12
    useScopeStore().setTeamScope(12, '旧チーム')
    const wrapper = await mountSuspended(AdminSettingsHub, { props: orgProps })
    await flushPromises()
    expect(useScopeStore().current).toMatchObject({ type: 'organization', id: '12' })
    expect(wrapper.find('[data-testid="setting-line"]').exists()).toBe(true)
    useScopeStore().setTeamScope(12, '旧チーム')
    await wrapper.vm.$nextTick()
    expect(wrapper.find('[data-testid="setting-line"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="setting-receipts"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it.each(['DEPUTY_ADMIN', 'MEMBER', 'SYSTEM_ADMIN', null])('AC3/4: exact ADMIN以外（%s）には新ハブ管理リンクを表示しない', async (value) => {
    role = value
    const wrapper = await mountSuspended(AdminSettingsHub, { props: teamProps })
    await flushPromises()
    expect(wrapper.findAll('[data-testid^="setting-"]')).toHaveLength(0)
    expect(api.mock.calls.map(([path]) => path)).toEqual(['/api/v1/teams/alpha/me/permissions'])
    wrapper.unmount()
  })

  it.each([
    null,
    { slug: 'alpha', id: 0 },
    { slug: 'alpha', id: Number.MAX_SAFE_INTEGER + 1 },
    { slug: 'old-team', id: 12 },
  ])('AC3/4: 本人所属の空/旧slug/不正IDは他団体をstoreへ登録せずglobal hrefを公開しない（%j）', async (identity) => {
    teamMembershipIdentity = identity
    useScopeStore().setTeamScope(12, '旧団体')
    const wrapper = await mountSuspended(AdminSettingsHub, { props: teamProps })
    await flushPromises()
    // 古いcurrentScopeのIDだけが一致しても、現在URLと本人所属が不一致なら許可しない。
    await wrapper.vm.$nextTick()
    expect(wrapper.find('[data-testid="setting-line"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="setting-receipts"]').exists()).toBe(false)
    expect(api.mock.calls.filter(([path]) => path === '/api/v1/me/teams')).toHaveLength(1)
    expect(useScopeStore().current.name).toBe('旧団体')
    wrapper.unmount()
  })

  it('AC4: 権限取得失敗を空/拒否に倒さず再試行し、成功後に設定を表示する', async () => {
    failPermissions = true
    const wrapper = await mountSuspended(AdminSettingsHub, { props: teamProps })
    await flushPromises()
    expect(wrapper.findAll('[data-testid^="setting-"]')).toHaveLength(0)
    expect(wrapper.find('[data-testid="load-error-state-retry"]').exists()).toBe(true)
    failPermissions = false
    await wrapper.get('[data-testid="load-error-state-retry"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-testid="setting-line"]').exists()).toBe(true)
    wrapper.unmount()
  })

  it('AC2/4: モジュール失敗は空と区別し、再試行後に領収書リンクを復元する', async () => {
    failModules = true
    const wrapper = await mountSuspended(AdminSettingsHub, { props: teamProps })
    await flushPromises()
    expect(wrapper.find('[data-testid="settings-module-error"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="setting-receipts"]').exists()).toBe(false)
    failModules = false
    await wrapper.get('[data-testid="settings-module-error-retry"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-testid="setting-receipts"]').exists()).toBe(true)
    wrapper.unmount()
  })

  it('AC3/4: 団体同期失敗ではglobal hrefを公開せず、再試行後に実団体を確認する', async () => {
    failMemberships = true
    const wrapper = await mountSuspended(AdminSettingsHub, { props: teamProps })
    await flushPromises()
    expect(wrapper.find('[data-testid="settings-scope-error"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="setting-line"]').exists()).toBe(false)
    failMemberships = false
    await wrapper.get('[data-testid="settings-scope-error-retry"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-testid="setting-line"]').exists()).toBe(true)
    expect(useScopeStore().current).toMatchObject({ type: 'team', id: '12' })
    wrapper.unmount()
  })

  it('AC3/4: 所属取得中のルート変更後に旧団体を登録せず、旧global hrefを公開しない', async () => {
    let finish: () => void = () => {}
    pendingMemberships = new Promise<void>((resolve) => { finish = resolve })
    const wrapper = await mountSuspended(AdminSettingsHub, { props: teamProps })
    await flushPromises()
    route.path = '/organizations/beta/admin/settings'
    finish()
    await flushPromises()
    expect(useScopeStore().current).toMatchObject({ type: 'personal', id: null })
    expect(wrapper.find('[data-testid="setting-line"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="setting-receipts"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('AC4: 権限取得中にunmountした旧ハブは戻った応答で同期を開始しない', async () => {
    let finish: () => void = () => {}
    pendingPermissions = new Promise<void>((resolve) => { finish = resolve })
    const wrapper = await mountSuspended(AdminSettingsHub, { props: teamProps })
    wrapper.unmount()
    finish()
    await flushPromises()
    expect(useScopeStore().current).toMatchObject({ type: 'personal', id: null })
    expect(api.mock.calls.map(([path]) => path)).toEqual(['/api/v1/teams/alpha/me/permissions'])
  })
})
