import { describe, expect, it, vi, beforeEach } from 'vitest'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { ref, computed } from 'vue'
import { useNuxtApp } from '#app'
import MemberTeamsPage from '~/pages/organizations/[slug]/member-teams.vue'

/**
 * F01.2.1 8-A/8-B — 所属チームタブ（member-teams）の FE-UT。
 *
 * 8-A（加盟の設定ボタン。組織 ADMIN かつ管理レンズ ON のときだけ出す。設計書 §13）:
 *   MT-01 ADMIN・管理レンズ ON のとき「加盟の設定」リンクが設定画面を指して出る（AC-L01 の導線①）
 *   MT-02 管理レンズ OFF のときは出ない
 *   MT-03 ADMIN でない（DEPUTY_ADMIN / MEMBER）なら出ない
 *
 * 8-B（view 切替。AC-K02）:
 *   MT-04 ADMIN・管理レンズ ON で view=applications / invites / restrictions なら、その view を出す（一覧は出さない）
 *   MT-05 view なしなら一覧と切替を出す
 *   MT-06 ADMIN でない者が view=applications を直打ちしたら一覧表示へ戻し（view を外す）、管理 API は呼ばない
 *   MT-07 管理レンズ OFF の ADMIN も同様に一覧へ戻す
 *   MT-08 未知の view は一覧へ戻す
 *   MT-09 切替で view クエリを付け替える（一覧は view を外す）
 *   MT-10 view=groups は枠（8-C が差し込む）を出す
 *   MT-11 加盟申請の件数をバッジに出す / 取得失敗はエラー印
 *   MT-12 ADMIN でない者には切替自体を出さず、件数も取りに行かない
 *   MT-13 view の中身で承認などが起きたら件数を取り直す
 */

const state = {
  isAdmin: true,
  adminLens: true,
  query: {} as Record<string, string>,
  /** 指定時は isAdmin より優先（SYSTEM_ADMIN のみのケース用）。 */
  roleName: null as string | null,
}
const refreshOrgTeams = vi.fn()
// useRouter を差し替えると Nuxt 本体（afterEach 等）が壊れるため、実ルーターのメソッドを spy する。
const replaceSpy = vi.fn()
const pushSpy = vi.fn()
const listApplications = vi.fn()

vi.mock('~/composables/useOrgShellContext', () => ({
  useOrgShellContext: () => ({
    orgTeams: ref([]),
    showTeamSearchLink: computed(() => false),
    // shell の isAdmin は SYSTEM_ADMIN も含む（ページは roleName === 'ADMIN' で見る）。
    isAdmin: computed(() => state.isAdmin || state.roleName === 'SYSTEM_ADMIN'),
    roleName: computed(() => state.roleName ?? (state.isAdmin ? 'ADMIN' : 'MEMBER')),
    adminLens: ref(state.adminLens),
    refreshOrgTeams,
  }),
}))

vi.mock('~/composables/useOrgTeamMembershipApi', () => ({
  useOrgTeamMembershipApi: () => ({ listApplications }),
}))

mockNuxtImport('useRoute', () => () => ({ params: { slug: 'org-1' }, query: state.query }))

const viewStub = (testid: string) => ({
  emits: ['loaded', 'changed'],
  template: `<div data-testid="${testid}"><button data-testid="${testid}-changed" @click="$emit('changed')" /></div>`,
})

const stubs = {
  OrgTeamGrid: { template: '<div data-testid="org-team-grid" />' },
  NuxtLink: { props: ['to'], template: '<a :href="to"><slot /></a>' },
  MemberTeamsApplicationsView: viewStub('applications-view'),
  MemberTeamsInvitesView: viewStub('invites-view'),
  MemberTeamsRestrictionsView: viewStub('restrictions-view'),
  MemberTeamsGroupsView: { template: '<div data-testid="groups-view" />' },
  MemberTeamsGuideModal: true,
}

async function mountPage() {
  const wrapper = await mountSuspended(MemberTeamsPage, { global: { stubs } })
  await flushPromises()
  return wrapper
}

describe('pages/organizations/[slug]/member-teams.vue', () => {
  beforeEach(() => {
    state.isAdmin = true
    state.adminLens = true
    state.query = {}
    state.roleName = null
    refreshOrgTeams.mockReset()
    refreshOrgTeams.mockResolvedValue(undefined)
    replaceSpy.mockReset()
    pushSpy.mockReset()
    const router = useNuxtApp().$router
    vi.spyOn(router, 'replace').mockImplementation(async (...args) => void replaceSpy(...args))
    vi.spyOn(router, 'push').mockImplementation(async (...args) => void pushSpy(...args))
    listApplications.mockReset()
    listApplications.mockResolvedValue({ items: [], total: 0, totalPages: 1 })
  })

  describe('加盟の設定ボタン（8-A）', () => {
    it('MT-01: ADMIN・管理レンズ ON のとき、設定画面を指す「加盟の設定」リンクが出る', async () => {
      const wrapper = await mountPage()
      const link = wrapper.find('[data-testid="team-affiliation-settings-link"]')
      expect(link.exists()).toBe(true)
      expect(link.attributes('href')).toBe('/organizations/org-1/settings/team-affiliation')
    })

    it('MT-02: 管理レンズ OFF のときは出ない', async () => {
      state.adminLens = false
      const wrapper = await mountPage()
      expect(wrapper.find('[data-testid="team-affiliation-settings-link"]').exists()).toBe(false)
    })

    it('MT-03: ADMIN でなければ出ない', async () => {
      state.isAdmin = false
      const wrapper = await mountPage()
      expect(wrapper.find('[data-testid="team-affiliation-settings-link"]').exists()).toBe(false)
    })
  })

  describe('view 切替（8-B）', () => {
    it.each([
      ['applications', 'applications-view'],
      ['invites', 'invites-view'],
      ['restrictions', 'restrictions-view'],
    ])('MT-04: ADMIN・管理レンズ ON で view=%s ならその view を出し、一覧は出さない', async (view, testid) => {
      state.query = { view }
      const wrapper = await mountPage()
      expect(wrapper.find(`[data-testid="${testid}"]`).exists()).toBe(true)
      expect(wrapper.find('[data-testid="org-team-grid"]').exists()).toBe(false)
      // mountSuspended 自身が replace('/') を呼ぶため、ページが呼んだ（クエリ付きの）replace だけを数える。
      expect(replaceSpy.mock.calls.filter(([arg]) => typeof arg === 'object')).toHaveLength(0)
    })

    it('MT-05: view なしなら一覧と切替を出す', async () => {
      const wrapper = await mountPage()
      expect(wrapper.find('[data-testid="org-team-grid"]').exists()).toBe(true)
      expect(wrapper.find('[data-testid="view-tab-applications"]').exists()).toBe(true)
      expect(wrapper.find('[data-testid="applications-view"]').exists()).toBe(false)
    })

    it('MT-06: ADMIN でない者が view=applications を直打ちしたら、一覧へ戻し管理 API は呼ばない', async () => {
      state.isAdmin = false
      state.query = { view: 'applications' }
      const wrapper = await mountPage()
      expect(wrapper.find('[data-testid="applications-view"]').exists()).toBe(false)
      expect(wrapper.find('[data-testid="org-team-grid"]').exists()).toBe(true)
      expect(replaceSpy).toHaveBeenCalledWith({ query: {} })
      expect(listApplications).not.toHaveBeenCalled()
    })

    it('MT-07: 管理レンズ OFF の ADMIN も、一覧へ戻す', async () => {
      state.adminLens = false
      state.query = { view: 'invites' }
      const wrapper = await mountPage()
      expect(wrapper.find('[data-testid="invites-view"]').exists()).toBe(false)
      expect(wrapper.find('[data-testid="org-team-grid"]').exists()).toBe(true)
      expect(replaceSpy).toHaveBeenCalledWith({ query: {} })
    })

    it('MT-08: 未知の view は一覧へ戻す', async () => {
      state.query = { view: 'foo' }
      const wrapper = await mountPage()
      expect(wrapper.find('[data-testid="org-team-grid"]').exists()).toBe(true)
      expect(replaceSpy).toHaveBeenCalledWith({ query: {} })
    })

    it('MT-09: 切替で view クエリを付け替える（一覧は view を外す）', async () => {
      state.query = { view: 'applications', keep: '1' }
      const wrapper = await mountPage()
      await wrapper.find('[data-testid="view-tab-invites"]').trigger('click')
      expect(pushSpy).toHaveBeenLastCalledWith({ query: { keep: '1', view: 'invites' } })
      await wrapper.find('[data-testid="view-tab-teams"]').trigger('click')
      expect(pushSpy).toHaveBeenLastCalledWith({ query: { keep: '1' } })
    })

    it('MT-10: view=groups は枠（8-C が差し込む）を出す', async () => {
      state.query = { view: 'groups' }
      const wrapper = await mountPage()
      expect(wrapper.find('[data-testid="groups-view"]').exists()).toBe(true)
      expect(wrapper.find('[data-testid="org-team-grid"]').exists()).toBe(false)
    })

    it('MT-11: 加盟申請の件数をバッジに出し、取得失敗ならエラー印を出す', async () => {
      listApplications.mockResolvedValue({ items: [], total: 4, totalPages: 1 })
      const ok = await mountPage()
      expect(ok.find('[data-testid="view-tab-badge"]').text()).toBe('4')

      listApplications.mockRejectedValue(new Error('500'))
      const ng = await mountPage()
      expect(ng.find('[data-testid="view-tab-badge-error"]').exists()).toBe(true)
    })

    it('MT-12: ADMIN でない者には切替を出さず、件数も取りに行かない', async () => {
      state.isAdmin = false
      const wrapper = await mountPage()
      expect(wrapper.find('[data-testid="view-tab-applications"]').exists()).toBe(false)
      expect(listApplications).not.toHaveBeenCalled()
    })

    it('MT-13: view の中身で承認などが起きたら件数を取り直す', async () => {
      state.query = { view: 'applications' }
      const wrapper = await mountPage()
      const before = listApplications.mock.calls.length
      listApplications.mockResolvedValue({ items: [], total: 1, totalPages: 1 })
      await wrapper.find('[data-testid="applications-view-changed"]').trigger('click')
      await flushPromises()
      expect(listApplications.mock.calls.length).toBe(before + 1)
      expect(wrapper.find('[data-testid="view-tab-badge"]').text()).toBe('1')
    })

    it('MT-14: SYSTEM_ADMIN のみ（対象組織の ADMIN でない）は view=applications を直打ちしても一覧へ戻し、切替・件数・設定リンクも出さない', async () => {
      state.isAdmin = false
      state.roleName = 'SYSTEM_ADMIN'
      state.query = { view: 'applications' }
      const wrapper = await mountPage()
      expect(wrapper.find('[data-testid="applications-view"]').exists()).toBe(false)
      expect(wrapper.find('[data-testid="org-team-grid"]').exists()).toBe(true)
      expect(wrapper.find('[data-testid="view-tab-applications"]').exists()).toBe(false)
      expect(wrapper.find('[data-testid="team-affiliation-settings-link"]').exists()).toBe(false)
      expect(listApplications).not.toHaveBeenCalled()
      expect(replaceSpy).toHaveBeenCalledWith({ query: {} })
    })

    it('MT-15: 承認・拒否が成立したら、親シェルの所属チーム一覧も取り直す', async () => {
      state.query = { view: 'applications' }
      const wrapper = await mountPage()
      expect(refreshOrgTeams).not.toHaveBeenCalled()
      await wrapper.find('[data-testid="applications-view-changed"]').trigger('click')
      await flushPromises()
      expect(refreshOrgTeams).toHaveBeenCalledTimes(1)
    })
  })
})
