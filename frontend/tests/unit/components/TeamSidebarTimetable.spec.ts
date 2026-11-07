import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import { ref, computed } from 'vue'
import { featureForSidebar } from '~/constants/featureDisplay'

/**
 * CMP-261105-1507 A6: チームサイドバーの「時間割」導線。
 *
 * pages/teams/[slug]/timetable.vue はあるのに、サイドバーにも表示方針の一覧にも時間割が無く、
 * URL を直打ちしないと辿り着けなかった。組織の結線に倣い、OPTIONAL モジュール timetable が
 * 有効なときだけメンバー以上に出す。
 *
 *  TEAM-SB-TT-001: timetable モジュール有効 + MEMBER ならリンクが出る
 *  TEAM-SB-TT-002: timetable モジュール無効（既定 OFF）ならリンクは出ない
 *  TEAM-SB-TT-003: SUPPORTER / GUEST には出ない
 *  TEAM-SB-TT-004: 表示方針の一覧でチームの時間割キーが解決できる
 *  TEAM-SB-TT-005: 6言語すべてに teamSidebar.item.timetable がある
 */

vi.setConfig({ testTimeout: 30000 })

mockNuxtImport('useScopeFeatureVisibility', () => () => ({
  loading: ref(false),
  error: ref(undefined),
  refresh: vi.fn(),
  isVisible: () => true,
}))

const roleName = ref<string | null>('MEMBER')
vi.mock('~/composables/useRoleAccess', () => ({
  useRoleAccess: () => ({
    roleName,
    isScopeAdmin: computed(() => roleName.value === 'ADMIN'),
    can: vi.fn(() => false),
    isAdmin: computed(() => roleName.value === 'ADMIN' || roleName.value === 'SYSTEM_ADMIN'),
    isAdminOrDeputy: computed(
      () => ['ADMIN', 'SYSTEM_ADMIN', 'DEPUTY_ADMIN'].includes(roleName.value ?? ''),
    ),
    loadPermissions: vi.fn().mockResolvedValue({ ok: true }),
  }),
}))

const enabledModules = ref<{ moduleSlug: string; isEnabled: boolean }[]>([])
vi.mock('~/composables/useModuleApi', () => ({
  useModuleApi: () => ({
    getTeamModules: () => Promise.resolve({ data: enabledModules.value }),
  }),
}))
vi.mock('~/composables/useOrganizationModuleApi', () => ({
  useOrganizationModuleApi: () => ({
    getOrganizationModules: () => Promise.resolve([]),
  }),
}))

const TeamSidebar = (await import('~/components/TeamSidebar.vue')).default

const TIMETABLE_HREF = 'href="/teams/my-team/timetable"'

async function mountSidebar() {
  const wrapper = await mountSuspended(TeamSidebar, { props: { teamId: 'my-team' } })
  await new Promise(resolve => setTimeout(resolve, 0))
  await wrapper.vm.$nextTick()
  return wrapper
}

describe('TeamSidebar 時間割導線', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    roleName.value = 'MEMBER'
    enabledModules.value = [{ moduleSlug: 'timetable', isEnabled: true }]
  })

  it('TEAM-SB-TT-001: timetable モジュール有効 + MEMBER ならリンクが出る', async () => {
    const wrapper = await mountSidebar()
    expect(wrapper.html()).toContain(TIMETABLE_HREF)
  })

  it('TEAM-SB-TT-002: timetable モジュール無効ならリンクは出ない', async () => {
    enabledModules.value = [{ moduleSlug: 'timetable', isEnabled: false }]
    const wrapper = await mountSidebar()
    expect(wrapper.html()).not.toContain(TIMETABLE_HREF)
  })

  it.each(['SUPPORTER', 'GUEST'])('TEAM-SB-TT-003: %s には出ない', async (role) => {
    roleName.value = role
    const wrapper = await mountSidebar()
    expect(wrapper.html()).not.toContain(TIMETABLE_HREF)
  })

  it('TEAM-SB-TT-004: 表示方針の一覧でチームの時間割キーが解決できる', () => {
    expect(featureForSidebar('timetable', 'team')).toBe('timetable')
    expect(featureForSidebar('timetable', 'organization')).toBe('timetable')
  })

  it('TEAM-SB-TT-005: 6言語すべてに teamSidebar.item.timetable がある', () => {
    for (const locale of ['ja', 'en', 'zh', 'ko', 'es', 'de']) {
      const json = JSON.parse(
        readFileSync(resolve(__dirname, `../../../app/locales/${locale}/team_sidebar.json`), 'utf-8'),
      )
      expect(json.teamSidebar?.item?.timetable ?? json.item?.timetable, locale).toBeTruthy()
    }
  })
})
