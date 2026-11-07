// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { defineComponent, ref } from 'vue'
import Widget from './WidgetAnnouncements.vue'
import { toAnnouncementItem } from '~/utils/announcementAdapter'

const { api, push } = vi.hoisted(() => ({ api: vi.fn(), push: vi.fn() }))
vi.mock('~/composables/useApi', () => ({ useApi: () => api }))
vi.mock('vue-router', () => ({ useRouter: () => ({ push }) }))
vi.mock('vue-i18n', () => ({ useI18n: () => ({ t: (key: string) => key }) }))
vi.mock('nuxt/app', () => ({ useNuxtApp: () => ({ $i18n: { t: (key: string) => key }, $toast: { add: vi.fn() } }) }))
vi.mock('~/composables/useErrorReport', () => ({ useErrorReport: () => ({ captureQuiet: vi.fn() }) }))
vi.mock('~/composables/useErrorHandler', () => ({ useErrorHandler: () => ({ handleApiError: vi.fn() }) }))
vi.mock('~/composables/useRoleAccess', () => ({ useRoleAccess: () => ({ isAdmin: false }) }))
vi.mock('~/composables/useAnnouncementPreview', () => ({ useAnnouncementPreview: () => ({
  state: ref('CLOSED'), preview: ref(null), item: ref(null), error: ref(null), trigger: ref(null),
  open: vi.fn(), close: vi.fn(), retry: vi.fn(), markDisplayed: vi.fn(),
}) }))
const Card = defineComponent({ template: '<div><slot name="header"/><slot /></div>' })
const Item = defineComponent({ props: ['item'], emits: ['click'], template: '<button @click="$emit(\'click\', item, $event.currentTarget)">{{ item.title }}</button>' })
let wrapper: VueWrapper | undefined
beforeEach(() => vi.resetAllMocks())
afterEach(() => { wrapper?.unmount(); wrapper = undefined })
function render() {
  wrapper = mount(Widget, { props: { scopeType: 'TEAM', scopeId: '2', initialItems: [toAnnouncementItem({
    id: 12, scopeType: 'TEAM', scopeId: 2, sourceType: 'TIMELINE_POST', sourceUrl: '/timeline/existing',
    titleCache: '従来のお知らせ', contentPreviewAvailable: false, isRead: false,
  })] }, global: { stubs: { DashboardWidgetCard: Card, AnnouncementItem: Item, Button: true,
    NuxtLink: true, PageLoading: true, DashboardErrorState: true, DashboardEmptyState: true, AnnouncementDetailModal: true,
  } } })
  return wrapper
}
describe('対象外のお知らせクリック回帰', () => {
  it.each([500, 429])('PREVIEW-18: read %sでもカードを消さず既存遷移を維持する', async (statusCode) => {
    api.mockRejectedValueOnce({ statusCode })
    const view = render()
    await view.findComponent(Item).trigger('click')
    await flushPromises()
    expect(view.findComponent(Item).exists()).toBe(true)
    expect(view.emitted('unavailable')).toBeUndefined()
    expect(push).toHaveBeenCalledWith('/timeline/existing')
  })
  it('PREVIEW-12/18: ANNOUNCE_001だけカードを消し親へ不可視を通知する', async () => {
    api.mockRejectedValueOnce({ statusCode: 404, data: { error: { code: 'ANNOUNCE_001' } } })
    const view = render()
    await view.findComponent(Item).trigger('click')
    await flushPromises()
    expect(view.findComponent(Item).exists()).toBe(false)
    expect(view.emitted('unavailable')).toEqual([[12]])
    expect(push).not.toHaveBeenCalled()
  })
})
