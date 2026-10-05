// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { defineComponent, reactive } from 'vue'
import TeamPanel from './DashboardTeamPanel.vue'
import OrgPanel from './DashboardOrgPanel.vue'

const mocks = vi.hoisted(() => ({ dashboard: vi.fn(), store: null as unknown }))
vi.mock('~/stores/useScopeDashboardStore', () => ({ useScopeDashboardStore: () => mocks.store }))
vi.mock('~/composables/useDashboardApi', () => ({ useDashboardApi: () => ({ getTeamDashboard: mocks.dashboard, getOrganizationDashboard: mocks.dashboard }) }))
const Announcements = defineComponent({ name: 'DashboardAnnouncements', props: ['items', 'scopeId'], emits: ['refresh', 'unavailable'], template: '<div />' })
let wrapper: VueWrapper | undefined
beforeEach(() => {
  vi.resetAllMocks()
  mocks.store = reactive({
    selectedTeamId: 'a', selectedOrgId: 'a',
    tabPages: { TEAM: { items: [{ slug: 'a', scopeId: '1' }, { slug: 'b', scopeId: '2' }] }, ORGANIZATION: { items: [{ slug: 'a', scopeId: '1' }, { slug: 'b', scopeId: '2' }] } },
    isAdminLensOn: () => false,
  })
})
afterEach(() => { wrapper?.unmount(); wrapper = undefined })
function render(component: typeof TeamPanel) {
  wrapper = mount(component, { global: { mocks: { $t: (key: string) => key }, stubs: {
    DashboardAnnouncements: Announcements, ScopeSearchForm: true, ScopeTabBar: true,
    DashboardScopeLensToggle: true, PageLoading: true, Message: true, DashboardEmptyState: true,
    DashboardAdminWidgetGrid: true, DashboardSwipeWidgetGrid: true,
  } } })
  return wrapper
}
describe.each([
  ['TEAM', TeamPanel, 'teamNotices', 'selectedTeamId'],
  ['ORGANIZATION', OrgPanel, 'orgNotices', 'selectedOrgId'],
] as const)('%s のお知らせ接続', (_scope, component, field, selected) => {
  it('PREVIEW-12: 親refreshで子が再生成されても取得不可カードを復活させない', async () => {
    mocks.dashboard.mockResolvedValue({ data: { [field]: [{ id: 12 }] } })
    const view = render(component)
    await flushPromises()
    const first = view.findComponent(Announcements)
    expect(first.props('items')).toEqual([{ id: 12 }])
    first.vm.$emit('unavailable', 12)
    first.vm.$emit('refresh')
    await flushPromises()
    const recreated = view.findComponent(Announcements)
    expect(recreated.exists()).toBe(true)
    expect(recreated.vm).not.toBe(first.vm)
    expect(recreated.props('items')).toEqual([])
  })
  it('PREVIEW-10/18: scope切替後に旧一覧応答を混入させない', async () => {
    let resolve!: (value: unknown) => void
    mocks.dashboard.mockReturnValueOnce(new Promise(value => { resolve = value }))
      .mockResolvedValueOnce({ data: { [field]: [{ id: 22 }] } })
    const view = render(component)
    ;(mocks.store as Record<string, unknown>)[selected] = 'b'
    await flushPromises()
    resolve({ data: { [field]: [{ id: 11 }] } })
    await flushPromises()
    expect(view.findComponent(Announcements).props('items')).toEqual([{ id: 22 }])
    expect(view.findComponent(Announcements).props('scopeId')).toBe('2')
  })
  it.each([null, undefined])('PREVIEW-18: 非表示の配信設定(null/省略=%s)を尊重する', async (notices) => {
    mocks.dashboard.mockResolvedValue({ data: notices === undefined ? {} : { [field]: notices } })
    const view = render(component)
    await flushPromises()
    expect(view.findComponent(Announcements).exists()).toBe(false)
  })
})
