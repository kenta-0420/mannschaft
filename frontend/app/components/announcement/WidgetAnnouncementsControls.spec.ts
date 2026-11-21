// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { defineComponent, ref } from 'vue'
import Widget from './WidgetAnnouncements.vue'
import { useAuthStore } from '~/stores/useAuthStore'
import type { AnnouncementFeedItem } from '~/types/announcement'

const state = vi.hoisted(() => ({ admin: false }))
vi.mock('~/composables/useApi', () => ({ useApi: () => vi.fn() }))
vi.mock('vue-router', () => ({ useRouter: () => ({ push: vi.fn() }) }))
vi.mock('vue-i18n', () => ({ useI18n: () => ({ t: (key: string) => key }) }))
vi.mock('nuxt/app', () => ({ useNuxtApp: () => ({ $i18n: { t: (key: string) => key }, $toast: { add: vi.fn() } }) }))
vi.mock('~/composables/useErrorReport', () => ({ useErrorReport: () => ({ captureQuiet: vi.fn() }) }))
vi.mock('~/composables/useErrorHandler', () => ({ useErrorHandler: () => ({ handleApiError: vi.fn() }) }))
vi.mock('~/composables/useRoleAccess', () => ({ useRoleAccess: () => ({ isAdmin: state.admin }) }))
vi.mock('~/composables/useAnnouncementPreview', () => ({ useAnnouncementPreview: () => ({
  state: ref('CLOSED'), preview: ref(null), item: ref(null), error: ref(null), trigger: ref(null),
  open: vi.fn(), close: vi.fn(), retry: vi.fn(), markDisplayed: vi.fn(),
}) }))

const Card = defineComponent({ template: '<div><slot name="header"/><slot /></div>' })
const Item = defineComponent({
  props: { item: { type: Object, required: true }, showPinControl: Boolean, canRemove: Boolean },
  template: '<div :data-testid="`item-${item.id}`" :data-pin="String(showPinControl)" :data-remove="String(canRemove)" />',
})

function make(id: number, author: AnnouncementFeedItem['author'], scopeId: string | number = 2): AnnouncementFeedItem {
  return {
    id, scopeType: 'TEAM', scopeId, sourceType: 'BLOG_POST', sourceId: id, sourceUrl: null, title: `項目${id}`,
    excerpt: null, priority: 'NORMAL', isPinned: false, pinnedAt: null, visibility: 'PUBLIC', author,
    sourceMeta: null, isRead: true, startsAt: null, expiresAt: null, createdAt: '2026-10-05T12:00:00',
  } as AnnouncementFeedItem
}
const me = { id: 7, displayName: '私', avatarUrl: null }
const other = { id: 8, displayName: '他人', avatarUrl: null }

let wrapper: VueWrapper | undefined
function render() {
  wrapper = mount(Widget, {
    props: { scopeType: 'TEAM', scopeId: '2', initialItems: [make(1, me), make(2, other), make(3, null), make(4, me, 99)] },
    global: { stubs: { DashboardWidgetCard: Card, AnnouncementItem: Item, Button: true,
      NuxtLink: true, PageLoading: true, DashboardErrorState: true, DashboardEmptyState: true, AnnouncementDetailModal: true } },
  })
  return wrapper
}
function flags(id: number) {
  const el = wrapper!.get(`[data-testid="item-${id}"]`)
  return { pin: el.attributes('data-pin'), remove: el.attributes('data-remove') }
}

describe('WidgetAnnouncements: ピン留め/解除の出し分け（D-3）', () => {
  beforeEach(() => {
    state.admin = false
    setActivePinia(createPinia())
    useAuthStore().user = { id: 7, email: 'a@example.com', fullName: '私', profileImageUrl: null }
  })
  afterEach(() => { wrapper?.unmount(); wrapper = undefined })

  // ダッシュボード API の teamNotices には作者情報が無く著者判定できないため、
  // ウィジェットは作者本人の解除を出さず ADMIN のみ（従来どおり）とする
  it('非 ADMIN には、著者情報があっても何も出ない', () => {
    render()
    for (const id of [1, 2, 3]) expect(flags(id)).toEqual({ pin: 'false', remove: 'false' })
  })

  it('ADMIN はスコープ内の全項目でピン留めと解除ができる', () => {
    state.admin = true
    render()
    for (const id of [1, 2, 3]) expect(flags(id)).toEqual({ pin: 'true', remove: 'true' })
  })

  it('他スコープの項目には、著者本人・ADMIN でも何も出ない', () => {
    render()
    expect(flags(4)).toEqual({ pin: 'false', remove: 'false' })
    wrapper?.unmount()
    state.admin = true
    render()
    expect(flags(4)).toEqual({ pin: 'false', remove: 'false' })
  })
})
