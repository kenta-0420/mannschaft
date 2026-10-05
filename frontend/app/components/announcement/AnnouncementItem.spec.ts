// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount, type VueWrapper } from '@vue/test-utils'
import { computed, defineComponent, ref } from 'vue'
import AnnouncementItem from './AnnouncementItem.vue'
import type { AnnouncementFeedItem } from '~/types/announcement'

const Button = defineComponent({ template: '<button type="button"><slot /></button>' })
let wrapper: VueWrapper | undefined
beforeEach(() => {
  vi.stubGlobal('computed', computed)
  vi.stubGlobal('ref', ref)
  vi.stubGlobal('useI18n', () => ({ t: (key: string) => key }))
  vi.stubGlobal('useRouter', () => ({ push: vi.fn() }))
  vi.stubGlobal('useDatetime', () => ({ formatDate: () => '日付', fromNow: () => '現在' }))
})
afterEach(() => {
  wrapper?.unmount()
  wrapper = undefined
  vi.unstubAllGlobals()
})

function render() {
  wrapper = mount(AnnouncementItem, {
    props: { item: {
      id: 1, scopeType: 'TEAM', scopeId: '2', sourceType: 'BLOG_POST', sourceId: 3,
      title: '広告のお知らせ', excerpt: null, priority: 'NORMAL', isPinned: false, isRead: false,
      createdAt: '2026-10-05T12:00:00', isAdvertisement: true, messagingCampaignId: 'campaign',
    } as AnnouncementFeedItem },
    global: { stubs: { Button, Menu: true, AdLabelBadge: true, AdReportModal: true } },
  })
  return wrapper
}

describe('PREVIEW-18: カードと独立操作のキー境界', () => {
  it.each(['Enter', ' '])('広告ボタンの%sは既定操作を妨げず本文を開かない', (key) => {
    const view = render()
    const button = view.get('[data-testid="ad-menu-trigger"]').element
    const event = new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true })
    button.dispatchEvent(event)
    expect(view.emitted('click')).toBeUndefined()
    expect(event.defaultPrevented).toBe(false)
  })
  it.each(['Enter', ' '])('カード自身の%sは既定操作を抑止し本文を一度開く', (key) => {
    const view = render()
    const event = new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true })
    view.element.dispatchEvent(event)
    expect(event.defaultPrevented).toBe(true)
    expect(view.emitted('click')).toHaveLength(1)
  })
})
