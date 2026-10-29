// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { defineComponent, ref } from 'vue'
import Widget from './WidgetAnnouncements.vue'
import type { AnnouncementFeedItem } from '~/types/announcement'
import { toAnnouncementItem } from '~/utils/announcementAdapter'

/**
 * CMP-261011-0035 ALIC-1 —「すべて既読」の対象を「取得済み一覧の未読」に揃える（M12）。
 * WidgetAnnouncements と useAnnouncementFeed は本物を使い、API だけを差し替える。
 *
 *   AC-D04/D06 ORGANIZATION 項目は組織ごとに未読 ID（描画範囲外も含む）を
 *              POST /api/v1/organizations/{orgId}/announcements/read-batch に {feedIds} で最大50件ずつ送る。
 *              TEAM 項目は従来の team read-all。組織の read-all は呼ばない。refresh 後はバッジ0。失敗時は既存エラー表示
 *   AC-D17     1回目押下 → 自動再取得 → 2回目押下で、2回目の read-all も呼ばれる（initialItems あり／composable 経路）
 */
const { api, handleApiError, toastAdd } = vi.hoisted(() => ({ api: vi.fn(), handleApiError: vi.fn(), toastAdd: vi.fn() }))
vi.mock('~/composables/useApi', () => ({ useApi: () => api }))
vi.mock('vue-router', () => ({ useRouter: () => ({ push: vi.fn() }) }))
vi.mock('vue-i18n', () => ({ useI18n: () => ({ t: (key: string, named?: { count?: number }) => named?.count !== undefined ? `${key}:${named.count}` : key }) }))
vi.mock('nuxt/app', () => ({ useNuxtApp: () => ({ $i18n: { t: (key: string) => key }, $toast: { add: toastAdd } }) }))
vi.mock('~/composables/useErrorReport', () => ({ useErrorReport: () => ({ captureQuiet: vi.fn() }) }))
vi.mock('~/composables/useErrorHandler', () => ({ useErrorHandler: () => ({ handleApiError }) }))
vi.mock('~/composables/useRoleAccess', () => ({ useRoleAccess: () => ({ isAdmin: false }) }))
vi.mock('~/composables/useAnnouncementPreview', () => ({ useAnnouncementPreview: () => ({
  state: ref('CLOSED'), preview: ref(null), item: ref(null), error: ref(null), trigger: ref(null),
  open: vi.fn(), close: vi.fn(), retry: vi.fn(), markDisplayed: vi.fn(),
}) }))

const Card = defineComponent({ template: '<div><slot name="header"/><slot /></div>' })
const Item = defineComponent({ props: ['item'], template: '<div data-testid="rendered-item" :data-id="item.id">{{ item.title }}</div>' })
const Button = defineComponent({
  props: ['label'], emits: ['click'],
  template: '<button :data-label="label" @click="$emit(\'click\')">{{ label }}</button>',
})
const stubs = { DashboardWidgetCard: Card, AnnouncementItem: Item, Button, NuxtLink: true, PageLoading: true,
  DashboardErrorState: true, DashboardEmptyState: true, AnnouncementDetailModal: true }

let wrapper: VueWrapper | undefined
beforeEach(() => vi.resetAllMocks())
afterEach(() => { wrapper?.unmount(); wrapper = undefined })

interface RawItem { id: number, scopeType: 'TEAM' | 'ORGANIZATION', scopeId: number, isPinned?: boolean, isRead?: boolean }
function raw({ id, scopeType, scopeId, isPinned = false, isRead = false }: RawItem) {
  return { id, scopeType, scopeId, sourceType: 'BLOG_POST' as const, sourceId: id + 1000, titleCache: `告知${id}`,
    contentPreviewAvailable: true, isPinned, isRead }
}
const item = (input: RawItem): AnnouncementFeedItem => toAnnouncementItem(raw(input))
const org = (id: number, extra: Partial<RawItem> = {}) => item({ id, scopeType: 'ORGANIZATION', scopeId: 9, ...extra })
const team = (id: number, extra: Partial<RawItem> = {}) => item({ id, scopeType: 'TEAM', scopeId: 2, ...extra })

/** ピン留め未読4（描画は3まで）＋通常未読2 = 組織の未読6。既読の組織項目1件は送らない。 */
function sixUnreadOrgItems(): AnnouncementFeedItem[] {
  return [
    org(101, { isPinned: true }), org(102, { isPinned: true }), org(103, { isPinned: true }), org(104, { isPinned: true }),
    org(105), org(106), org(107, { isRead: true }),
  ]
}

function mountWithItems(items: AnnouncementFeedItem[]) {
  wrapper = mount(Widget, { props: { scopeType: 'TEAM', scopeId: '2', initialItems: items }, global: { stubs } })
  return wrapper
}
const markAllButton = (view: VueWrapper) => view.find('button[data-label="announcement.mark_all_read"]')
const badgeText = (view: VueWrapper) => view.text().match(/announcement\.unread_count:(\d+)/)?.[1]
const calls = (suffix: string) => api.mock.calls.filter(([url]) => String(url).endsWith(suffix))
const batchCalls = () => api.mock.calls.filter(([url]) => String(url).endsWith('/announcements/read-batch'))
const sentIds = (call: unknown[]) => ((call[1] as { body?: { feedIds?: number[] } })?.body?.feedIds ?? [])

describe('「すべて既読」— initialItems あり（ダッシュボード経路）', () => {
  it('AC-ALIC1-D06: バッジは取得済み一覧の未読6（描画外の4件目のピン留めも数える）', () => {
    const view = mountWithItems(sixUnreadOrgItems())
    expect(view.findAll('[data-testid="rendered-item"]').map(w => w.attributes('data-id'))).not.toContain('104')
    expect(badgeText(view)).toBe('6')
  })

  it('AC-ALIC1-D06: 組織の未読6件（描画外含む）の ID を read-batch へ送り、組織 read-all は呼ばない', async () => {
    api.mockResolvedValue({ data: { markedCount: 6 } })
    const view = mountWithItems(sixUnreadOrgItems())
    await markAllButton(view).trigger('click')
    await flushPromises()
    const batches = batchCalls()
    expect(batches).toHaveLength(1)
    expect(batches[0]?.[0]).toBe('/api/v1/organizations/9/announcements/read-batch')
    expect((batches[0]?.[1] as { method?: string }).method).toBe('POST')
    expect([...sentIds(batches[0]!)].sort()).toEqual([101, 102, 103, 104, 105, 106])
    expect(calls('/organizations/9/announcements/read-all')).toHaveLength(0)
    expect(view.emitted('refresh')).toHaveLength(1)
  })

  it('AC-ALIC1-D04: TEAM 項目は team read-all、ORGANIZATION 項目は read-batch、refresh 後バッジ0', async () => {
    api.mockResolvedValue({ data: { markedCount: 1, hasMoreUnread: false } })
    const items = [team(201), team(202, { isRead: true }), org(101), org(102, { isPinned: true })]
    const view = mountWithItems(items)
    expect(badgeText(view)).toBe('3')
    await markAllButton(view).trigger('click')
    await flushPromises()
    expect(calls('/teams/2/announcements/read-all')).toHaveLength(1)
    expect(calls('/organizations/9/announcements/read-all')).toHaveLength(0)
    expect(batchCalls().map(c => [...sentIds(c)].sort())).toEqual([[101, 102]])
    expect(view.emitted('refresh')).toHaveLength(1)
    await view.setProps({ initialItems: items.map(value => ({ ...value, isRead: true })) })
    expect(badgeText(view)).toBeUndefined()
  })

  it('AC-ALIC1-D06: 組織が複数あれば組織ごとに read-batch を分ける', async () => {
    api.mockResolvedValue({ data: { markedCount: 1 } })
    const view = mountWithItems([org(101), item({ id: 301, scopeType: 'ORGANIZATION', scopeId: 10 })])
    await markAllButton(view).trigger('click')
    await flushPromises()
    const byUrl = Object.fromEntries(batchCalls().map(c => [String(c[0]), sentIds(c)]))
    expect(byUrl).toEqual({
      '/api/v1/organizations/9/announcements/read-batch': [101],
      '/api/v1/organizations/10/announcements/read-batch': [301],
    })
  })

  it('AC-ALIC1-D06: 組織の未読が51件以上なら50件ずつに分割して全件を送る', async () => {
    api.mockResolvedValue({ data: { markedCount: 50 } })
    const ids = Array.from({ length: 120 }, (_, i) => 1000 + i)
    const view = mountWithItems(ids.map(id => org(id)))
    await markAllButton(view).trigger('click')
    await flushPromises()
    const sizes = batchCalls().map(c => sentIds(c).length).sort((a, b) => b - a)
    expect(sizes).toEqual([50, 50, 20])
    expect(batchCalls().flatMap(c => sentIds(c)).sort((a, b) => a - b)).toEqual(ids)
  })

  it('AC-ALIC1-D06: read-batch 失敗は既存のエラー表示（handleApiError）に出す', async () => {
    const failure = { statusCode: 500 }
    api.mockImplementation(async (url: string) => {
      if (url.endsWith('/read-batch')) throw failure
      return { data: { markedCount: 0 } }
    })
    const view = mountWithItems(sixUnreadOrgItems())
    await markAllButton(view).trigger('click')
    await flushPromises()
    expect(batchCalls()).toHaveLength(1)
    expect(handleApiError).toHaveBeenCalledWith(failure)
  })

  it('AC-ALIC1-D17（回帰）: 1回目 → 親の再取得 → 2回目の押下で team read-all が2回呼ばれる', async () => {
    api.mockResolvedValue({ data: { markedCount: 500, hasMoreUnread: true } })
    const items = [team(201), team(202)]
    const view = mountWithItems(items)
    await markAllButton(view).trigger('click')
    await flushPromises()
    expect(view.emitted('refresh')).toHaveLength(1)
    // 親が再取得し、BE に未読が残っていたので未読のまま渡し直す
    await view.setProps({ initialItems: items.map(value => ({ ...value })) })
    await markAllButton(view).trigger('click')
    await flushPromises()
    expect(calls('/teams/2/announcements/read-all')).toHaveLength(2)
    expect(view.emitted('refresh')).toHaveLength(2)
  })
})

describe('「すべて既読」— initialItems なし（composable 経路）', () => {
  function feedResponse(items: RawItem[], unreadCount: number) {
    return { data: items.map(raw), meta: { unreadCount, nextCursor: null, hasNext: false } }
  }

  it('AC-ALIC1-D17（回帰）: read-all が打ち切り（hasMoreUnread）→ 自動再取得 → 2回目の押下でも read-all を呼ぶ', async () => {
    const unread: RawItem[] = [{ id: 201, scopeType: 'TEAM', scopeId: 2 }, { id: 202, scopeType: 'TEAM', scopeId: 2 }]
    api.mockImplementation(async (url: string, options?: { method?: string }) => {
      if (options?.method === 'POST') return { data: { markedCount: 500, hasMoreUnread: true } }
      return feedResponse(unread, 2)
    })
    wrapper = mount(Widget, { props: { scopeType: 'TEAM', scopeId: '2' }, global: { stubs } })
    await flushPromises()
    await markAllButton(wrapper).trigger('click')
    await flushPromises()
    const getsAfterFirst = api.mock.calls.filter(([, options]) => !(options as { method?: string } | undefined)?.method).length
    expect(getsAfterFirst).toBe(2)
    await markAllButton(wrapper).trigger('click')
    await flushPromises()
    expect(calls('/teams/2/announcements/read-all')).toHaveLength(2)
  })

  it('AC-ALIC1-D06: 組織スコープの一覧では取得済み未読を read-batch に送り、組織 read-all は呼ばない', async () => {
    const items: RawItem[] = [{ id: 101, scopeType: 'ORGANIZATION', scopeId: 9 }, { id: 102, scopeType: 'ORGANIZATION', scopeId: 9 },
      { id: 103, scopeType: 'ORGANIZATION', scopeId: 9, isRead: true }]
    api.mockImplementation(async (url: string, options?: { method?: string }) => {
      if (options?.method === 'POST') return { data: { markedCount: 2 } }
      return feedResponse(items, 2)
    })
    wrapper = mount(Widget, { props: { scopeType: 'ORGANIZATION', scopeId: '9' }, global: { stubs } })
    await flushPromises()
    await markAllButton(wrapper).trigger('click')
    await flushPromises()
    expect(calls('/organizations/9/announcements/read-all')).toHaveLength(0)
    expect(batchCalls().map(c => [...sentIds(c)].sort())).toEqual([[101, 102]])
  })
})
