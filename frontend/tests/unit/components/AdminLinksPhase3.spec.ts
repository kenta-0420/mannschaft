import { describe, it, expect, beforeAll, beforeEach, vi } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import { ref, computed } from 'vue'

/**
 * CMP-260909-1141 Phase 3 のユニットテスト。
 *
 * <p>運営管理ページ群への導線を2系統で検証する:</p>
 * <ul>
 *   <li>テナント ADMIN 向け5枚 → TeamSidebar / OrganizationSidebar の absolutePath</li>
 *   <li>SYSTEM_ADMIN 向け4枚 → SystemAdminQuickLinks の quickLinks</li>
 * </ul>
 *
 * テストケース一覧:
 *  P3-TEAM-001: DEPUTY_ADMIN + repair_longterm_plan 有効なら vendors がクエリ付きで出る
 *  P3-TEAM-002: repair_longterm_plan 無効なら vendors は出ない（moduleSlug 判定が効いている証明）
 *  P3-TEAM-003: MEMBER には vendors・line-settings 等の DEPUTY_ADMIN 限定リンクが出ない
 *  P3-TEAM-004: DEPUTY_ADMIN なら line/sns/schedule-settings・bulletin-categories が絶対パスで出る
 *  P3-ORG-001: 組織サイドバーでも vendors がクエリ付きで出る（repair_longterm_plan は ORGANIZATION でも有効）
 *  P3-QL-001: SYSTEM_ADMIN 向け4枚が SystemAdminQuickLinks に出る
 */

const roleName = ref<string | null>('ADMIN')
vi.mock('~/composables/useRoleAccess', () => ({
  useRoleAccess: () => ({
    roleName,
    isAdmin: computed(() => roleName.value === 'ADMIN'),
    isAdminOrDeputy: computed(
      () => roleName.value === 'ADMIN' || roleName.value === 'DEPUTY_ADMIN',
    ),
    loadPermissions: vi.fn().mockResolvedValue(undefined),
  }),
}))

const enabledModules = ref<{ moduleSlug: string, isEnabled: boolean }[]>([])
vi.mock('~/composables/useOrganizationModuleApi', () => ({
  useOrganizationModuleApi: () => ({
    getOrganizationModules: () => Promise.resolve(enabledModules.value),
  }),
}))
vi.mock('~/composables/useModuleApi', () => ({
  useModuleApi: () => ({
    getTeamModules: () => Promise.resolve({ data: enabledModules.value }),
  }),
}))

const TeamSidebar = (await import('~/components/TeamSidebar.vue')).default
const OrganizationSidebar = (await import('~/components/OrganizationSidebar.vue')).default
const SystemAdminQuickLinks = (await import('~/components/system-admin/SystemAdminQuickLinks.vue')).default

async function mountTeamSidebar() {
  const wrapper = await mountSuspended(TeamSidebar, { props: { teamId: 'team-1' } })
  await new Promise(resolve => setTimeout(resolve, 0))
  await wrapper.vm.$nextTick()
  return wrapper
}

async function mountOrgSidebar() {
  const wrapper = await mountSuspended(OrganizationSidebar, { props: { orgId: 'org-1' } })
  await new Promise(resolve => setTimeout(resolve, 0))
  await wrapper.vm.$nextTick()
  return wrapper
}

describe('Phase 3 運営管理ページ導線', () => {
  // mountSuspended の初回マウントが timeout を食う既知の罠への対処（既存作法に倣うウォームアップ）。
  beforeAll(async () => {
    roleName.value = 'ADMIN'
    enabledModules.value = [{ moduleSlug: 'repair_longterm_plan', isEnabled: true }]
    await mountTeamSidebar()
  }, 30000)

  beforeEach(() => {
    vi.clearAllMocks()
    roleName.value = 'ADMIN'
    enabledModules.value = [{ moduleSlug: 'repair_longterm_plan', isEnabled: true }]
  })

  it('P3-TEAM-001: DEPUTY_ADMIN + repair_longterm_plan 有効なら vendors がクエリ付きで出る', async () => {
    roleName.value = 'DEPUTY_ADMIN'
    const wrapper = await mountTeamSidebar()
    expect(wrapper.html()).toContain('href="/admin/vendors?scope=teams&amp;scopeId=team-1"')
  })

  it('P3-TEAM-002: repair_longterm_plan 無効なら vendors は出ない', async () => {
    enabledModules.value = [{ moduleSlug: 'repair_longterm_plan', isEnabled: false }]
    const wrapper = await mountTeamSidebar()
    expect(wrapper.html()).not.toContain('/admin/vendors')
  })

  it('P3-TEAM-003: MEMBER には DEPUTY_ADMIN 限定リンクが出ない', async () => {
    roleName.value = 'MEMBER'
    const wrapper = await mountTeamSidebar()
    const html = wrapper.html()
    expect(html).not.toContain('/admin/vendors')
    expect(html).not.toContain('/admin/line-settings')
    expect(html).not.toContain('/admin/sns-settings')
    expect(html).not.toContain('/admin/schedule-settings')
    // カテゴリ CRUD は BE が requireManageContent（DEPUTY_ADMIN 付与判定）を要求するため、
    // 一般 MEMBER に見せると押しても 403 になる導線を作ることになる。
    expect(html).not.toContain('/admin/bulletin-categories')
  })

  it('P3-TEAM-004: DEPUTY_ADMIN なら line/sns/schedule-settings・bulletin-categories が出る', async () => {
    roleName.value = 'DEPUTY_ADMIN'
    enabledModules.value = [
      { moduleSlug: 'repair_longterm_plan', isEnabled: true },
      { moduleSlug: 'bulletin', isEnabled: true },
    ]
    const wrapper = await mountTeamSidebar()
    const html = wrapper.html()
    expect(html).toContain('href="/admin/line-settings"')
    expect(html).toContain('href="/admin/sns-settings"')
    expect(html).toContain('href="/admin/schedule-settings"')
    expect(html).toContain('href="/admin/bulletin-categories"')
  })

  it('P3-ORG-001: 組織サイドバーでも vendors がクエリ付きで出る', async () => {
    roleName.value = 'DEPUTY_ADMIN'
    const wrapper = await mountOrgSidebar()
    expect(wrapper.html()).toContain('href="/admin/vendors?scope=organizations&amp;scopeId=org-1"')
  })
})

describe('Phase 3 SYSTEM_ADMIN クイックリンク', () => {
  async function mountQuickLinks() {
    return mountSuspended(SystemAdminQuickLinks)
  }

  it('P3-QL-001: 4枚のリンクがすべて出る', async () => {
    const wrapper = await mountQuickLinks()
    const html = wrapper.html()
    expect(html).toContain('href="/admin/villages/creation-requests"')
    expect(html).toContain('href="/admin/seasonal-wallpapers"')
    expect(html).toContain('href="/admin/point-card-synonyms"')
    expect(html).toContain('href="/admin/appeals"')
  })
})
