import { describe, expect, it, vi, beforeEach } from 'vitest'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import DiscoverOrganizationsPage from '~/pages/discover/organizations.vue'

/**
 * F01.2.1 8-A（AC-A10 の画面側）— 公開組織検索の「チーム加盟を受付中」絞り込みの FE-UT。
 *
 * 検証観点:
 *   DO-01 URL クエリ acceptingTeamApplications=true で開くと、チームが ON で初期表示され、検索にも反映される
 *   DO-02 クエリが無ければ OFF で、検索には acceptingTeamApplications を渡さない
 *   DO-03 チップを押すと ON になり、その条件で再検索し、URL クエリも更新する
 *   DO-04 ON のチップをもう一度押すと OFF に戻り、URL クエリから外れる
 */

const searchPublicOrganizations = vi.fn()
let replace: ReturnType<typeof vi.spyOn>
const route = { params: {}, query: {} as Record<string, string> }

vi.mock('~/composables/usePublicApi', () => ({
  usePublicApi: () => ({ searchPublicOrganizations }),
}))

mockNuxtImport('useRoute', () => () => route)

const EMPTY_PAGE = { content: [], totalPages: 0, totalElements: 0, number: 0, size: 20 }

async function mountPage() {
  const wrapper = await mountSuspended(DiscoverOrganizationsPage, {
    global: { stubs: { DiscoverOrganizationCard: true } },
  })
  await flushPromises()
  return wrapper
}

function chip(wrapper: Awaited<ReturnType<typeof mountPage>>) {
  return wrapper.find('[data-testid="filter-accepting-chip"]')
}

describe('pages/discover/organizations.vue — 受付中の絞り込み', () => {
  beforeEach(() => {
    searchPublicOrganizations.mockReset()
    searchPublicOrganizations.mockResolvedValue(EMPTY_PAGE)
    // 実ルーターの replace だけを差し替える（useRouter 自体を mock すると Nuxt 自身のプラグインが壊れる）
    replace = vi.spyOn(useRouter(), 'replace').mockResolvedValue(undefined)
    route.query = {}
  })

  it('DO-01: クエリ acceptingTeamApplications=true で開くとチップが ON で、検索にも反映される', async () => {
    route.query = { acceptingTeamApplications: 'true' }
    const wrapper = await mountPage()
    expect(chip(wrapper).attributes('aria-pressed')).toBe('true')
    expect(searchPublicOrganizations).toHaveBeenCalled()
    expect(searchPublicOrganizations.mock.calls[0]![0].acceptingTeamApplications).toBe(true)
  })

  it('DO-02: クエリが無ければ OFF で、検索に acceptingTeamApplications を渡さない', async () => {
    const wrapper = await mountPage()
    expect(chip(wrapper).attributes('aria-pressed')).toBe('false')
    expect(searchPublicOrganizations.mock.calls[0]![0].acceptingTeamApplications).toBeUndefined()
  })

  it('DO-03: チップを押すと ON になり、その条件で再検索し、URL クエリを更新する', async () => {
    const wrapper = await mountPage()
    searchPublicOrganizations.mockClear()
    await chip(wrapper).trigger('click')
    await flushPromises()
    expect(chip(wrapper).attributes('aria-pressed')).toBe('true')
    expect(searchPublicOrganizations.mock.calls.at(-1)![0].acceptingTeamApplications).toBe(true)
    expect(replace).toHaveBeenCalledWith({ query: { acceptingTeamApplications: 'true' } })
  })

  it('DO-04: ON のチップをもう一度押すと OFF に戻り、URL クエリから外れる', async () => {
    route.query = { acceptingTeamApplications: 'true' }
    const wrapper = await mountPage()
    await chip(wrapper).trigger('click')
    await flushPromises()
    expect(chip(wrapper).attributes('aria-pressed')).toBe('false')
    expect(searchPublicOrganizations.mock.calls.at(-1)![0].acceptingTeamApplications).toBeUndefined()
    expect(replace).toHaveBeenCalledWith({ query: {} })
  })
})
