// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { defineComponent, ref } from 'vue'
import Widget from './WidgetAnnouncements.vue'
import DashboardWidgetCard from '../DashboardWidgetCard.vue'
import { toAnnouncementItem } from '~/utils/announcementAdapter'

/**
 * CMP-261011-0035 — 告知カードの見出し・未読バッジ・「すべて既読」・「すべてのお知らせ」が
 * 本物の DashboardWidgetCard 上で描画されること（card を stub にすると header スロットの欠落を見逃す）。
 */
const { api, handleApiError } = vi.hoisted(() => ({ api: vi.fn(), handleApiError: vi.fn() }))
vi.mock('~/composables/useApi', () => ({ useApi: () => api }))
vi.mock('vue-router', () => ({ useRouter: () => ({ push: vi.fn() }) }))
vi.mock('vue-i18n', () => ({ useI18n: () => ({ t: (key: string, named?: { count?: number }) => named?.count !== undefined ? `${key}:${named.count}` : key }) }))
vi.mock('nuxt/app', () => ({ useNuxtApp: () => ({ $i18n: { t: (key: string) => key }, $toast: { add: vi.fn() } }) }))
vi.mock('~/composables/useErrorReport', () => ({ useErrorReport: () => ({ captureQuiet: vi.fn() }) }))
vi.mock('~/composables/useErrorHandler', () => ({ useErrorHandler: () => ({ handleApiError }) }))
vi.mock('~/composables/useRoleAccess', () => ({ useRoleAccess: () => ({ isAdmin: false }) }))
vi.mock('~/composables/useAnnouncementPreview', () => ({ useAnnouncementPreview: () => ({
  state: ref('CLOSED'), preview: ref(null), item: ref(null), error: ref(null), trigger: ref(null),
  open: vi.fn(), close: vi.fn(), retry: vi.fn(), markDisplayed: vi.fn(),
}) }))

const Button = defineComponent({
  props: ['label', 'icon'], emits: ['click'],
  template: '<button :data-label="label" :data-icon="icon" @click="$emit(\'click\')">{{ label }}</button>',
})
const Link = defineComponent({ props: ['to'], template: '<a :href="to" data-testid="link"><slot /></a>' })
const stubs = { DashboardWidgetCard, Button, NuxtLink: Link, AnnouncementItem: true, PageLoading: true,
  DashboardErrorState: true, DashboardEmptyState: true, AnnouncementDetailModal: true, Skeleton: true }

let wrapper: VueWrapper | undefined
beforeEach(() => vi.resetAllMocks())
afterEach(() => { wrapper?.unmount(); wrapper = undefined })

const item = (id: number) => toAnnouncementItem({ id, scopeType: 'TEAM', scopeId: 2, sourceType: 'BLOG_POST' as const,
  sourceId: id + 1000, titleCache: `告知${id}`, contentPreviewAvailable: true, isPinned: false, isRead: false })

describe('WidgetAnnouncements の見出し（本物の DashboardWidgetCard）', () => {
  it('見出し・未読バッジ・すべて既読・再読込・すべてのお知らせが描画され、押すと既読処理が呼ばれる', async () => {
    api.mockResolvedValue({ data: { markedCount: 2, hasMoreUnread: false } })
    wrapper = mount(Widget, {
      props: { scopeType: 'TEAM', scopeId: '2', scopeSlug: 'alpha', initialItems: [item(1), item(2)] },
      global: { stubs },
    })
    expect(wrapper.find('[data-announcement-heading]').exists()).toBe(true)
    expect(wrapper.find('[data-announcement-heading]').attributes('tabindex')).toBe('-1')
    expect(wrapper.text()).toContain('announcement.unread_count:2')
    expect(wrapper.find('a[href="/teams/alpha/announcements"]').exists()).toBe(true)
    expect(wrapper.find('button[data-icon="pi pi-refresh"]').exists()).toBe(true)
    // 折り畳みボタンは header スロット指定時も残る
    expect(wrapper.find('button[data-icon="pi pi-minus"]').exists()).toBe(true)
    const markAll = wrapper.find('button[data-label="announcement.mark_all_read"]')
    expect(markAll.exists()).toBe(true)
    await markAll.trigger('click')
    await flushPromises()
    expect(api.mock.calls.some(([url]) => String(url).endsWith('/teams/2/announcements/read-all'))).toBe(true)
  })
})

describe('DashboardWidgetCard の header スロット', () => {
  it('header スロット指定時は title 見出しの代わりに描画し、折り畳みボタンを残す', () => {
    wrapper = mount(DashboardWidgetCard, {
      props: { title: '無視される' },
      slots: { header: '<span data-testid="custom-header">独自見出し</span>', default: '本文' },
      global: { stubs: { Button, NuxtLink: Link, Skeleton: true } },
    })
    expect(wrapper.find('[data-testid="custom-header"]').exists()).toBe(true)
    expect(wrapper.text()).not.toContain('無視される')
    expect(wrapper.find('button[data-icon="pi pi-minus"]').exists()).toBe(true)
  })

  it('header スロット単独（title なし）でもヘッダー行が描画される', () => {
    wrapper = mount(DashboardWidgetCard, {
      slots: { header: '<span data-testid="custom-header">独自見出し</span>', default: '本文' },
      global: { stubs: { Button, NuxtLink: Link, Skeleton: true } },
    })
    expect(wrapper.find('[data-testid="custom-header"]').exists()).toBe(true)
  })

  it('header スロット未指定で title があれば従来どおり h3 見出しを描画する', () => {
    wrapper = mount(DashboardWidgetCard, {
      props: { title: '従来の見出し' }, slots: { default: '本文' },
      global: { stubs: { Button, NuxtLink: Link, Skeleton: true } },
    })
    expect(wrapper.find('h3').text()).toBe('従来の見出し')
  })
})
