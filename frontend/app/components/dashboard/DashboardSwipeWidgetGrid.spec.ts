// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount, type VueWrapper } from '@vue/test-utils'
import { defineComponent, ref } from 'vue'
import type { TeamDashboardResponse, OrgDashboardResponse } from '~/types/dashboard-scope'
import Grid from './DashboardSwipeWidgetGrid.vue'
import Item from './DashboardScopeWidgetItem.vue'

const mocks = vi.hoisted(() => ({ open: vi.fn(), close: vi.fn(), retry: vi.fn() }))
vi.mock('vue-i18n', () => ({ useI18n: () => ({ t: (key: string) => key }) }))
vi.mock('~/composables/useDatetime', () => ({
  useDatetime: () => ({
    formatDate: (value: string) => value,
    formatDateTime: (value: string) => value,
  }),
}))
vi.mock('~/composables/useScopeWidgetPreview', () => ({
  useScopeWidgetPreview: () => ({
    state: ref('CLOSED'),
    target: ref(null),
    detail: ref(null),
    error: ref(null),
    trigger: ref(null),
    ...mocks,
  }),
}))
const ListWidget = defineComponent({
  props: ['items', 'to'],
  template:
    '<section><slot v-for="item in items" name="item" :item="item" /><a v-if="to" :href="to">all</a></section>',
})
let wrapper: VueWrapper | undefined
beforeEach(() => vi.resetAllMocks())
afterEach(() => wrapper?.unmount())
function render(scopeType: 'TEAM' | 'ORGANIZATION', empty = false) {
  const fields = {
    UpcomingEvents: [
      { id: 1, title: '予定', start_at: '2026-10-24T10:00:00+09:00', all_day: false },
    ],
    UnreadThreads: {
      bulletin_threads: [
        { id: 2, title: '掲示板', updated_at: '2026-10-22T10:00:00+09:00', is_read: false },
      ],
    },
    LatestBlogPosts: [{ id: 3, slug: 'post', title: 'ブログ' }],
    ChatSummary: {
      channels: [{ id: 4, name: 'チャット', unread_count: 1, last_message_preview: '最新文' }],
    },
    Todo: { items: [{ id: 5, title: 'TODO', due_date: null }] },
    CalendarSummary: {
      events_today: 0,
      events_this_week: 0,
      next_event: null,
      days_with_events: [],
    },
  }
  const prefix = scopeType === 'TEAM' ? 'team' : 'org'
  const data = Object.fromEntries(
    Object.entries(fields).map(([key, value]) => [prefix + key, empty ? null : value]),
  ) as unknown as TeamDashboardResponse | OrgDashboardResponse
  wrapper = mount(Grid, {
    props: { scopeType, scopeId: 'my-scope', numericScopeId: '7', data },
    global: {
      mocks: { $t: (key: string) => key },
      stubs: {
        DashboardScopeListWidget: ListWidget,
        DashboardScopeWidgetItem: Item,
        DashboardWidgetCard: { template: '<section><slot /></section>' },
        TimelineFeed: true,
        ScopeActionRequiredWidget: true,
        DashboardScopePreviewModal: true,
        Tag: true,
        Badge: true,
        DashboardEmptyState: true,
      },
    },
  })
  return wrapper
}

describe.each(['TEAM', 'ORGANIZATION'] as const)('%s グリッドの導線', (scopeType) => {
  it('AC1/8/9: 5種の項目はネイティブbuttonで、開く前に取得せず選択を渡す', async () => {
    const view = render(scopeType)
    expect(mocks.open).not.toHaveBeenCalled()
    for (const [kind, id] of [
      ['schedule', 1],
      ['bulletin', 2],
      ['blog', 3],
      ['chat', 4],
      ['todo', 5],
    ] as const) {
      const button = view.get(`[data-testid="scope-widget-${kind}-${id}"]`)
      expect(button.element.tagName).toBe('BUTTON')
      expect(button.attributes('type')).toBe('button')
      expect(button.attributes('aria-haspopup')).toBe('dialog')
      expect(button.classes()).toContain('min-h-11')
      await button.trigger('click')
      expect(mocks.open).toHaveBeenLastCalledWith(
        expect.objectContaining({ kind, id }),
        button.element,
      )
    }
    expect(view.findAll('a').map((link) => link.attributes('href'))).toContain(
      `/${scopeType === 'TEAM' ? 'teams' : 'organizations'}/my-scope/schedule`,
    )
    expect(view.findComponent({ name: 'TimelineFeed' }).exists()).toBe(true)
    expect(view.findComponent({ name: 'ScopeActionRequiredWidget' }).exists()).toBe(true)
  })
  it('表示OFFの機能は子widgetをmountせず全要対応OFFでは追加取得入口も消す', async () => {
    const view = render(scopeType)
    await view.setProps({ isFeatureVisible: () => false })
    expect(view.find('button').exists()).toBe(false)
    expect(view.findComponent({ name: 'TimelineFeed' }).exists()).toBe(false)
    expect(view.findComponent({ name: 'ScopeActionRequiredWidget' }).exists()).toBe(false)
    await view.setProps({ isFeatureVisible: (key: string) => key === 'surveys' })
    const actions = view.findComponent({ name: 'ScopeActionRequiredWidget' })
    expect(actions.exists()).toBe(true)
    expect(actions.attributes('sections')).toBeDefined()
  })

  it('AC4: カレンダー0件も概要を開き、null時は架空項目を出さない', async () => {
    const view = render(scopeType)
    await view.get('[data-testid="scope-widget-calendar"]').trigger('click')
    expect(mocks.open).toHaveBeenLastCalledWith(
      expect.objectContaining({
        kind: 'calendar',
        calendar: expect.objectContaining({ events_today: 0, next_event: null }),
      }),
      expect.any(HTMLElement),
    )
    view.unmount()
    const empty = render(scopeType, true)
    expect(empty.find('button').exists()).toBe(false)
  })
})
