import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { computed } from 'vue'
import { useNuxtApp } from '#app'
import TeamAffiliationsPage from '~/pages/teams/[slug]/affiliations.vue'

/**
 * F01.2.1 8-D — /teams/{slug}/affiliations（チーム側の加盟先の管理）の FE-UT。
 *
 *   TA-01 加盟操作者でなければ画面を出さず、view も管理 API も呼ばない（AC-K03・P02）
 *   TA-02 加盟操作者は既定で所属組織 view を出す
 *   TA-03 ?view=invites で受信招待 view に直接着地できる（AC-L06 / 通知リンク）
 *   TA-04 ?view=applications / restrictions も直接着地できる
 *   TA-05 未知の view は view を外した URL へ戻す
 *   TA-06 切替で view クエリを付け替える（所属組織は view を外す）
 *   TA-07 「加盟先の組織を探す」が受付中の組織の検索を指す（AC-L02 導線）
 *   TA-08 受信招待の件数をバッジに出す
 *   TA-09 使い方モーダルを開ける（G143）
 */

const state = {
  canManage: true,
  query: {} as Record<string, string>,
}
const replaceSpy = vi.fn()
const pushSpy = vi.fn()
const listInvites = vi.fn()

vi.mock('~/composables/useTeamShellContext', () => ({
  useTeamShellContext: () => ({
    canManageOrgAffiliation: computed(() => state.canManage),
    displayName: computed(() => 'チームA'),
  }),
}))

vi.mock('~/composables/useTeamAffiliationApi', () => ({
  useTeamAffiliationApi: () => ({ listInvites }),
}))

mockNuxtImport('useRoute', () => () => ({ params: { slug: 'team-1' }, query: state.query, path: '/teams/team-1/affiliations' }))

const viewStub = (testid: string) => ({
  props: ['teamSlug'],
  emits: ['loaded', 'changed'],
  template: `<div data-testid="${testid}"><button data-testid="${testid}-changed" @click="$emit('changed')" /></div>`,
})

const stubs = {
  NuxtLink: { props: ['to'], template: '<a :href="to"><slot /></a>' },
  TeamAffiliationsOrganizationsView: viewStub('organizations-view'),
  TeamAffiliationsInvitesView: viewStub('invites-view'),
  TeamAffiliationsApplicationsView: viewStub('applications-view'),
  TeamAffiliationsRestrictionsView: viewStub('restrictions-view'),
  TeamAffiliationsGuideModal: { props: ['visible'], template: '<div v-if="visible" data-testid="guide-modal" />' },
}

async function mountPage() {
  const wrapper = await mountSuspended(TeamAffiliationsPage, { global: { stubs } })
  await flushPromises()
  return wrapper
}

describe('pages/teams/[slug]/affiliations.vue', () => {
  beforeEach(() => {
    state.canManage = true
    state.query = {}
    replaceSpy.mockReset()
    pushSpy.mockReset()
    const router = useNuxtApp().$router
    vi.spyOn(router, 'replace').mockImplementation(async (...args) => void replaceSpy(...args))
    vi.spyOn(router, 'push').mockImplementation(async (...args) => void pushSpy(...args))
    listInvites.mockReset()
    listInvites.mockResolvedValue({ items: [], total: 0, totalPages: 1 })
  })

  const pageReplaces = () => replaceSpy.mock.calls.filter(([arg]) => typeof arg === 'object')

  it('TA-01: 加盟操作者でなければ画面を出さず、管理 API も呼ばない', async () => {
    state.canManage = false
    state.query = { view: 'invites' }
    const w = await mountPage()
    expect(w.find('[data-testid="invites-view"]').exists()).toBe(false)
    expect(w.find('[data-testid="organizations-view"]').exists()).toBe(false)
    expect(w.find('[data-testid="team-affiliations-switcher"]').exists()).toBe(false)
    expect(listInvites).not.toHaveBeenCalled()
  })

  it('TA-02: 既定は所属組織 view', async () => {
    const w = await mountPage()
    expect(w.find('[data-testid="organizations-view"]').exists()).toBe(true)
    expect(w.find('[data-testid="team-affiliations-switcher"]').exists()).toBe(true)
  })

  it('TA-03: ?view=invites で受信招待 view に着地できる', async () => {
    state.query = { view: 'invites' }
    const w = await mountPage()
    expect(w.find('[data-testid="invites-view"]').exists()).toBe(true)
    expect(w.find('[data-testid="organizations-view"]').exists()).toBe(false)
    expect(pageReplaces()).toHaveLength(0)
  })

  it.each([
    ['applications', 'applications-view'],
    ['restrictions', 'restrictions-view'],
  ])('TA-04: ?view=%s に着地できる', async (view, testid) => {
    state.query = { view }
    const w = await mountPage()
    expect(w.find(`[data-testid="${testid}"]`).exists()).toBe(true)
  })

  it('TA-05: 未知の view は view を外した URL へ戻す', async () => {
    state.query = { view: 'bogus', foo: 'bar' }
    const w = await mountPage()
    expect(w.find('[data-testid="organizations-view"]').exists()).toBe(true)
    expect(pageReplaces()).toHaveLength(1)
    expect(pageReplaces()[0]![0]).toEqual({ query: { foo: 'bar' } })
  })

  it('TA-06: 切替で view クエリを付け替える', async () => {
    const w = await mountPage()
    await w.find('[data-testid="view-tab-invites"]').trigger('click')
    expect(pushSpy).toHaveBeenCalledWith({ query: { view: 'invites' } })
    state.query = { view: 'invites' }
  })

  it('TA-06b: 所属組織へ戻すときは view を外す', async () => {
    state.query = { view: 'invites' }
    const w = await mountPage()
    await w.find('[data-testid="view-tab-organizations"]').trigger('click')
    expect(pushSpy).toHaveBeenCalledWith({ query: {} })
  })

  it('TA-07: 「加盟先の組織を探す」が受付中の組織の検索を指す', async () => {
    const w = await mountPage()
    const link = w.find('[data-testid="discover-organizations-link"]')
    expect(link.exists()).toBe(true)
    expect(link.attributes('href')).toBe('/discover/organizations?acceptingTeamApplications=true')
  })

  it('TA-08: 受信招待の件数をバッジに出す', async () => {
    listInvites.mockResolvedValue({ items: [{ id: 1 }, { id: 2 }], total: 2, totalPages: 1 })
    const w = await mountPage()
    expect(listInvites).toHaveBeenCalledWith('team-1', 0, 1)
    expect(w.find('[data-testid="view-tab-badge"]').text()).toBe('2')
  })

  it('TA-09: 使い方モーダルを開ける', async () => {
    const w = await mountPage()
    expect(w.find('[data-testid="guide-modal"]').exists()).toBe(false)
    await w.find('[data-testid="page-header-help"]').trigger('click')
    expect(w.find('[data-testid="guide-modal"]').exists()).toBe(true)
  })
})
