import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import { ref, computed } from 'vue'

/**
 * CMP-261012-0313 F-AC1 / F-AC2: チームサイドバーの「スキル・資格」導線のユニットテスト。
 *
 * 資格画面（/teams/{slug}/skills）はこれまでアプリ内のどこからも辿り着けなかった。
 * 機能ゲート FEATURE_SKILL_RESUME_ENABLED が ON のときだけ、メンバー以上に導線を出す。
 *
 * テストケース一覧:
 *  TEAM-SB-SKL-001: ゲート ON + MEMBER + member_intro 有効ならリンクが出る
 *  TEAM-SB-SKL-002: ゲート OFF ならリンクは出ない（死んだ導線を作らない）
 *  TEAM-SB-SKL-003: SYSTEM_ADMIN でもゲート OFF ならリンクは出ない
 *  TEAM-SB-SKL-004: フラグ未取得（キー無し）なら fail-closed でリンクは出ない
 *  TEAM-SB-SKL-005: SYSTEM_ADMIN でもゲート ON ならリンクが出る
 *  TEAM-SB-SKL-006: member_intro モジュール無効ならリンクは出ない
 *  TEAM-SB-SKL-007: ゲート OFF でも既存項目（メンバー紹介）は回帰せず出る
 *  TEAM-SB-SKL-009: SUPPORTER / GUEST にはリンクが出ない
 *  TEAM-SB-SKL-010: MEMBER / DEPUTY_ADMIN / ADMIN にはリンクが出る
 *  TEAM-SB-SKL-008: 6言語すべてのロケールに teamSidebar.item.skills がある
 */

vi.setConfig({ testTimeout: 30000 })

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
const { useFeatureFlagStore } = await import('~/stores/featureFlags')

const GATE = 'FEATURE_SKILL_RESUME_ENABLED'
const SKILLS_HREF = 'href="/teams/my-team/skills"'
const PROFILES_HREF = 'href="/teams/my-team/member-profiles"'

async function mountSidebar() {
  const wrapper = await mountSuspended(TeamSidebar, { props: { teamId: 'my-team' } })
  await new Promise(resolve => setTimeout(resolve, 0))
  await wrapper.vm.$nextTick()
  return wrapper
}

function setFlags(flags: Record<string, boolean>) {
  useFeatureFlagStore().flags = flags
}

describe('TeamSidebar スキル・資格導線', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    roleName.value = 'MEMBER'
    enabledModules.value = [{ moduleSlug: 'member_intro', isEnabled: true }]
    setFlags({ [GATE]: true })
  })

  it('TEAM-SB-SKL-001: ゲート ON + MEMBER ならスキル・資格リンクが出る', async () => {
    const wrapper = await mountSidebar()
    expect(wrapper.html()).toContain(SKILLS_HREF)
  })

  it('TEAM-SB-SKL-002: ゲート OFF ならリンクは出ない', async () => {
    setFlags({ [GATE]: false })
    const wrapper = await mountSidebar()
    expect(wrapper.html()).not.toContain(SKILLS_HREF)
  })

  it('TEAM-SB-SKL-003: SYSTEM_ADMIN でもゲート OFF なら出ない', async () => {
    roleName.value = 'SYSTEM_ADMIN'
    setFlags({ [GATE]: false })
    const wrapper = await mountSidebar()
    expect(wrapper.html()).not.toContain(SKILLS_HREF)
  })

  it('TEAM-SB-SKL-004: フラグ未取得なら（fail-closed）出ない', async () => {
    setFlags({})
    const wrapper = await mountSidebar()
    expect(wrapper.html()).not.toContain(SKILLS_HREF)
  })

  it('TEAM-SB-SKL-005: SYSTEM_ADMIN でもゲート ON なら出る', async () => {
    roleName.value = 'SYSTEM_ADMIN'
    const wrapper = await mountSidebar()
    expect(wrapper.html()).toContain(SKILLS_HREF)
  })

  it('TEAM-SB-SKL-006: member_intro モジュール無効なら出ない', async () => {
    enabledModules.value = [{ moduleSlug: 'member_intro', isEnabled: false }]
    const wrapper = await mountSidebar()
    expect(wrapper.html()).not.toContain(SKILLS_HREF)
  })

  it('TEAM-SB-SKL-007: ゲート OFF でも既存項目（メンバー紹介）は出る（回帰なし）', async () => {
    setFlags({ [GATE]: false })
    const wrapper = await mountSidebar()
    expect(wrapper.html()).toContain(PROFILES_HREF)
  })

  it.each(['SUPPORTER', 'GUEST'])(
    'TEAM-SB-SKL-009: %s にはゲート ON・member_intro 有効でもスキル・資格リンクは出ない',
    async (role) => {
      roleName.value = role
      const wrapper = await mountSidebar()
      expect(wrapper.html()).not.toContain(SKILLS_HREF)
    },
  )

  it.each(['MEMBER', 'DEPUTY_ADMIN', 'ADMIN'])(
    'TEAM-SB-SKL-010: %s にはゲート ON・member_intro 有効ならスキル・資格リンクが出る',
    async (role) => {
      roleName.value = role
      const wrapper = await mountSidebar()
      expect(wrapper.html()).toContain(SKILLS_HREF)
    },
  )

  it('TEAM-SB-SKL-008: 6言語すべてに teamSidebar.item.skills がある', () => {
    for (const locale of ['ja', 'en', 'zh', 'ko', 'es', 'de']) {
      const json = JSON.parse(
        readFileSync(resolve(process.cwd(), `app/locales/${locale}/team_sidebar.json`), 'utf8'),
      ) as { teamSidebar: { item: Record<string, string> } }
      expect(json.teamSidebar.item.skills, `${locale}`).toBeTruthy()
    }
  })
})
