// @vitest-environment happy-dom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { mount, type VueWrapper } from '@vue/test-utils'
import { defineComponent } from 'vue'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import AnnouncementItem from './AnnouncementItem.vue'
import type { AnnouncementFeedItem } from '~/types/announcement'

vi.mock('vue-i18n', () => ({ useI18n: () => ({ t: (key: string) => key }) }))
vi.mock('~/composables/useDatetime', () => ({ useDatetime: () => ({ formatDate: () => '日付', fromNow: () => '現在' }) }))
mockNuxtImport('useRouter', () => () => ({ push: vi.fn() }))

const Button = defineComponent({ template: '<button type="button"><slot /></button>' })
let wrapper: VueWrapper | undefined
afterEach(() => {
  wrapper?.unmount()
  wrapper = undefined
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

describe('AC-H17: 送信履歴（一覧）の宛先表示', () => {
  function renderWith(targetAudience: AnnouncementFeedItem['targetAudience']) {
    wrapper = mount(AnnouncementItem, {
      props: { item: {
        id: 1, scopeType: 'ORGANIZATION', scopeId: '2', sourceType: 'BLOG_POST', sourceId: 3,
        title: 'グループ宛て', excerpt: null, priority: 'NORMAL', isPinned: false, isRead: false,
        createdAt: '2026-10-05T12:00:00', targetAudience,
      } as AnnouncementFeedItem },
      global: { stubs: { Button, Menu: true, AdLabelBadge: true, AdReportModal: true } },
    })
    return wrapper
  }

  it('宛先の記録があれば「宛先: …（送信時 N チーム）」と表示判定の注記を出す', () => {
    const view = renderWith({
      mode: 'GROUPS', groups: [{ id: 'g1', name: '平成18年度' }], includeUnassigned: false, teamCount: 12, directMemberCount: 1,
    })
    expect(view.get('[data-testid="broadcast-audience-summary"]').text()).toContain('announcement.audience_summary')
    expect(view.find('[data-testid="broadcast-audience-display-note"]').exists()).toBe(true)
  })

  it('宛先の記録が無い告知では出さない', () => {
    expect(renderWith(null).find('[data-testid="broadcast-audience-summary"]').exists()).toBe(false)
  })
})
