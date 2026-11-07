// @vitest-environment happy-dom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, nextTick, ref } from 'vue'
import { mount } from '@vue/test-utils'
import { toAnnouncementItem } from '~/utils/announcementAdapter'
import { useAnnouncementFeed } from './useAnnouncementFeed'
const { api } = vi.hoisted(() => ({ api: vi.fn() }))
vi.mock('~/composables/useApi', () => ({ useApi: () => api }))
vi.mock('~/composables/useErrorReport', () => ({ useErrorReport: () => ({ captureQuiet: vi.fn() }) }))
vi.mock('nuxt/app', () => ({ useNuxtApp: () => ({ $i18n: { t: (key: string) => key }, $toast: { add: vi.fn() } }) }))

const item = () => toAnnouncementItem({ id: 12, scopeType: 'ORGANIZATION', scopeId: 7,
  sourceType: 'BLOG_POST', titleCache: '組織配信', isRead: false })
beforeEach(() => vi.resetAllMocks())

describe('本文プレビュー後の一覧更新', () => {
  it.each(['heading', 'neighbor', 'modal', 'other', 'duringTick', 'detached'] as const)('PREVIEW-11/13: 閉じ後read404のカード除去時focus(%s)', async (destination) => {
    const feed = useAnnouncementFeed('TEAM', '8')
    feed.setFeedItemsLocally(destination === 'neighbor' ? [item(), { ...item(), id: 13 }] : [item()])
    const host = mount(defineComponent({
      setup: () => ({ items: feed.feed }),
      template: '<div data-announcement-list><span tabindex="-1" data-announcement-heading>一覧</span><button v-for="entry in items" :key="entry.id" data-announcement-item :data-announcement-id="entry.id">{{ entry.id }}</button></div>',
    }), { attachTo: document.body })
    const other = document.createElement('button')
    const dialog = document.createElement('section')
    dialog.setAttribute('role', 'dialog')
    const inside = document.createElement('button')
    dialog.append(inside)
    document.body.append(other, dialog)
    try {
      const original = host.get('[data-announcement-id="12"]').element as HTMLElement
      // after-hideが元カードへ戻した後、開始済みread404がカードを除去する順序。
      original.focus()
      expect(document.activeElement).toBe(original)
      if (destination === 'modal') inside.focus()
      if (destination === 'other') other.focus()
      feed.removeFromFeedLocally(12)
      if (destination === 'duringTick') other.focus()
      if (destination === 'detached') host.element.remove()
      await nextTick()
      expect(host.find('[data-announcement-id="12"]').exists()).toBe(false)
      const expected = destination === 'heading' ? host.get('[data-announcement-heading]').element
        : destination === 'neighbor' ? host.get('[data-announcement-id="13"]').element
          : destination === 'modal' ? inside : destination === 'detached' ? document.body : other
      expect(document.activeElement).toBe(expected)
    }
    finally { host.unmount(); other.remove(); dialog.remove() }
  })

  it('PREVIEW-05/12: 不可視カードは親initialItemsの再取得で復活しない', () => {
    const feed = useAnnouncementFeed('TEAM', '8')
    feed.setFeedItemsLocally([item()])
    feed.removeFromFeedLocally(12)
    feed.setFeedItemsLocally([item()])
    expect(feed.feed.value).toEqual([])
    expect(api).not.toHaveBeenCalled()
  })

  it('PREVIEW-05/12: 不可視カードは一覧GET成功でも復活せず未読数を減らす', async () => {
    const feed = useAnnouncementFeed('TEAM', '8')
    feed.setFeedItemsLocally([item()])
    feed.removeFromFeedLocally(12)
    api.mockResolvedValue({ data: [item()], meta: { unreadCount: 1, hasNext: false } })
    await feed.fetchFeed()
    expect(feed.feed.value).toEqual([])
    expect(feed.meta.value?.unreadCount).toBe(0)
  })

  it('PREVIEW-02/11: 集約の組織feed操作は親TEAMではなく所有scopeへ送る', async () => {
    const feed = useAnnouncementFeed('TEAM', '8')
    feed.setFeedItemsLocally([item()])
    api.mockResolvedValue({ data: {} })
    await feed.markAsRead(12)
    expect(api.mock.calls[0]?.[0]).toBe('/api/v1/organizations/7/announcements/12/read')
    expect(feed.feed.value[0]?.isRead).toBe(true)
  })

  it('PREVIEW-11: 既読成功の一覧callbackは一度だけカウントを減らす', () => {
    const feed = useAnnouncementFeed('TEAM', '8')
    feed.setFeedItemsLocally([item()])
    feed.meta.value = { nextCursor: null, limit: 20, totalCount: 1, hasNext: false, unreadCount: 1 }
    feed.setReadLocally(12)
    feed.setReadLocally(12)
    expect(feed.meta.value.unreadCount).toBe(0)
    expect(feed.feed.value[0]?.isRead).toBe(true)
    expect(api).not.toHaveBeenCalled()
  })

  it('PREVIEW-02: チームslug解決後のref実IDで一覧GETする', async () => {
    const scopeId = ref('')
    const feed = useAnnouncementFeed('TEAM', scopeId)
    scopeId.value = '8'
    api.mockResolvedValue({ data: [], meta: { unreadCount: 0 } })
    await feed.fetchFeed()
    expect(api.mock.calls[0]?.[0]).toBe('/api/v1/teams/8/announcements')
  })
})
