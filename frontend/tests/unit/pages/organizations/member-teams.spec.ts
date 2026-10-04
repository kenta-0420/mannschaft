import { describe, expect, it, vi, beforeEach } from 'vitest'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import { ref, computed } from 'vue'
import MemberTeamsPage from '~/pages/organizations/[slug]/member-teams.vue'

/**
 * F01.2.1 8-A — 所属チームタブ（member-teams）の「加盟の設定」ボタンの FE-UT。
 *
 * ボタンは組織 ADMIN かつ管理レンズ ON のときだけ出す（設計書 §13）。
 *
 * 検証観点:
 *   MT-01 ADMIN・管理レンズ ON のとき「加盟の設定」リンクが設定画面を指して出る（AC-L01 の導線①）
 *   MT-02 管理レンズ OFF のときは出ない
 *   MT-03 ADMIN でない（DEPUTY_ADMIN / MEMBER）なら出ない
 */

const state = { isAdmin: true, adminLens: true }

vi.mock('~/composables/useOrgShellContext', () => ({
  useOrgShellContext: () => ({
    orgTeams: ref([]),
    showTeamSearchLink: computed(() => false),
    isAdmin: computed(() => state.isAdmin),
    adminLens: ref(state.adminLens),
  }),
}))

mockNuxtImport('useRoute', () => () => ({ params: { slug: 'org-1' }, query: {} }))

const stubs = {
  OrgTeamGrid: true,
  NuxtLink: { props: ['to'], template: '<a :href="to"><slot /></a>' },
}

describe('pages/organizations/[slug]/member-teams.vue — 加盟の設定ボタン', () => {
  beforeEach(() => {
    state.isAdmin = true
    state.adminLens = true
  })

  it('MT-01: ADMIN・管理レンズ ON のとき、設定画面を指す「加盟の設定」リンクが出る', async () => {
    const wrapper = await mountSuspended(MemberTeamsPage, { global: { stubs } })
    const link = wrapper.find('[data-testid="team-affiliation-settings-link"]')
    expect(link.exists()).toBe(true)
    expect(link.attributes('href')).toBe('/organizations/org-1/settings/team-affiliation')
  })

  it('MT-02: 管理レンズ OFF のときは出ない', async () => {
    state.adminLens = false
    const wrapper = await mountSuspended(MemberTeamsPage, { global: { stubs } })
    expect(wrapper.find('[data-testid="team-affiliation-settings-link"]').exists()).toBe(false)
  })

  it('MT-03: ADMIN でなければ出ない', async () => {
    state.isAdmin = false
    const wrapper = await mountSuspended(MemberTeamsPage, { global: { stubs } })
    expect(wrapper.find('[data-testid="team-affiliation-settings-link"]').exists()).toBe(false)
  })
})
