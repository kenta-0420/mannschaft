import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises } from '@vue/test-utils'
import { reactive } from 'vue'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import { useScopeStore } from '~/stores/useScopeStore'
import { useTeamStore } from '~/stores/useTeamStore'
import { useOrganizationStore } from '~/stores/useOrganizationStore'
import AdminSettingsHub from '~/components/admin/AdminSettingsHub.vue'

// モックは通信境界のみ。ロール解決、所属一覧、scope同期、表示部品は本物を動かす。
let role: string | null = 'ADMIN'
let payment = true
let failPermissions = false
let failModules = false
let failMemberships = false
let pendingPermissions: Promise<void> | null = null
let pendingMemberships: Promise<void> | null = null
const route = reactive({ path: '/teams/alpha/admin/settings' })
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
    return { data: [{ id: 12, slug: 'alpha', name: 'チームA', role: 'ADMIN' }] }
  }
  if (path === '/api/v1/me/organizations') {
    if (failMemberships) throw { statusCode: 503 }
    return { data: [{ id: 7, slug: 'beta', name: '組織B', role: 'ADMIN' }] }
  }
  throw new Error(`Unexpected API: ${path}`)
})
mockNuxtImport('useApi', () => () => api)
mockNuxtImport('useI18n', () => () => ({ t: (key: string) => key }))
mockNuxtImport('useRoute', () => () => route)

const teamProps = { scopeType: 'team' as const, slug: 'alpha', resolvedSlug: 'alpha', numericId: 12 }
const orgProps = { scopeType: 'organization' as const, slug: 'beta', resolvedSlug: 'beta', numericId: 7 }

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
  route.path = '/teams/alpha/admin/settings'
  api.mockClear()
  useScopeStore().clear()
  useTeamStore().clear()
  useOrganizationStore().clear()
})

describe('ADMIN設定ハブの既存導線と団体境界', () => {
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

  it.each(['DEPUTY_ADMIN', 'MEMBER', 'SYSTEM_ADMIN', null])('AC3/4: exact ADMIN以外（%s）には新ハブ管理リンクを表示しない', async (value) => {
    role = value
    const wrapper = await mountSuspended(AdminSettingsHub, { props: teamProps })
    await flushPromises()
    expect(wrapper.findAll('[data-testid^="setting-"]')).toHaveLength(0)
    expect(api.mock.calls.map(([path]) => path)).toEqual(['/api/v1/teams/alpha/me/permissions'])
    wrapper.unmount()
  })

  it.each([
    { resolvedSlug: undefined, numericId: undefined },
    { resolvedSlug: 'alpha', numericId: 0 },
    { resolvedSlug: 'alpha', numericId: Number.MAX_SAFE_INTEGER + 1 },
    { resolvedSlug: 'old-team', numericId: 12 },
  ])('AC3/4: 未確定/旧slug/不正IDは他団体をstoreへ登録せずglobal hrefを公開しない（%j）', async (identity) => {
    const wrapper = await mountSuspended(AdminSettingsHub, { props: { ...teamProps, ...identity } })
    await flushPromises()
    // 旧shellと旧storeのIDだけが一致していても、現在URLのslugと違えば許可しない。
    useScopeStore().setTeamScope(12, '旧団体')
    await wrapper.vm.$nextTick()
    expect(wrapper.find('[data-testid="setting-line"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="setting-receipts"]').exists()).toBe(false)
    expect(api.mock.calls.some(([path]) => path === '/api/v1/me/teams')).toBe(false)
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
