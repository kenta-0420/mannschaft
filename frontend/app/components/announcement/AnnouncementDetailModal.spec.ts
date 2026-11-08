// @vitest-environment happy-dom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { mount, type VueWrapper } from '@vue/test-utils'
import { defineComponent } from 'vue'
import DetailModal from './AnnouncementDetailModal.vue'
import type { AnnouncementPreviewResponse, AnnouncementPreviewState } from '~/composables/useAnnouncementPreview'

vi.mock('vue-i18n', () => ({ useI18n: () => ({ t: (key: string) => key }) }))
vi.mock('~/composables/useBulletinApi', () => ({ useBulletinApi: () => ({ getAttachmentDownloadUrl: vi.fn() }) }))
vi.mock('~/composables/useErrorHandler', () => ({ useErrorHandler: () => ({ handleApiError: vi.fn() }) }))
let wrapper: VueWrapper | undefined
afterEach(() => { wrapper?.unmount(); wrapper = undefined })
const Dialog = defineComponent({ props: ['header'], template: '<section><h2>{{ header }}</h2><slot /><slot name="footer" /></section>' })
const full: AnnouncementPreviewResponse = {
  feedId: 1, scopeType: 'TEAM', scopeId: 2, accessState: 'FULL', sourceType: 'BLOG_POST', sourceId: 3,
  sourceUrl: '/blog/posts/latest?teamId=2', blogPost: { id: 3, content: { title: '最新タイトル', body: '' } },
  bulletinThread: null, attachments: [],
}
function render(state: AnnouncementPreviewState, preview: AnnouncementPreviewResponse | null) {
  wrapper = mount(DetailModal, { props: { state, preview, itemTitle: '古いfeedタイトル' }, global: { stubs: {
    Dialog, Button: true, NuxtLink: true, PageLoading: true, DashboardErrorState: true,
  } } })
  return wrapper
}
describe('本文モーダルの描画契約', () => {
  it.each(['', null, undefined])('PREVIEW-01/03: FULLは最新ブログ見出しと空/null/省略本文(%s)表示を使う', (body) => {
    const response = { ...full, blogPost: { id: 3, content: { title: '最新タイトル', body } } } as AnnouncementPreviewResponse
    const view = render('FULL', response)
    expect(view.find('h2').text()).toBe('最新タイトル')
    expect(view.find('[data-testid="announcement-preview-empty"]').exists()).toBe(true)
  })
  it('PREVIEW-01: FULLは最新スレッド見出しを使う', () => {
    const view = render('FULL', { ...full, sourceType: 'BULLETIN_THREAD', blogPost: null, bulletinThread: { id: 3, title: '最新スレッド', body: '<p>内容</p>' } })
    expect(view.find('h2').text()).toBe('最新スレッド')
  })
  it.each(['LOADING', 'LOCKED', 'ERROR'] as const)('PREVIEW-07/09: %sはfeed見出しを使い元リンクを出さない', (state) => {
    const view = render(state, null)
    expect(view.find('h2').text()).toBe('古いfeedタイトル')
    expect(view.find('[data-testid="announcement-preview-source"]').exists()).toBe(false)
  })
  it('PREVIEW-13: 最後の発火カードが消滅した場合は一覧見出しへフォーカスを戻す', async () => {
    const list = document.createElement('div')
    list.dataset.announcementList = ''
    const heading = document.createElement('span')
    heading.dataset.announcementHeading = ''
    heading.tabIndex = -1
    const trigger = document.createElement('div')
    trigger.dataset.announcementItem = ''
    trigger.tabIndex = 0
    list.append(heading, trigger)
    document.body.append(list)
    const scroll = vi.spyOn(window, 'scrollTo').mockImplementation(() => {})
    try {
      const view = render('FULL', full)
      await view.setProps({ trigger })
      view.findComponent(Dialog).vm.$emit('show')
      trigger.remove()
      view.findComponent(Dialog).vm.$emit('after-hide')
      expect(document.activeElement).toBe(heading)
    }
    finally { list.remove(); scroll.mockRestore() }
  })

  // ── CMP-261011-0035 ALIC-1 AC-B07（FE）: 委任 FULL は元ページボタンを出さず注記を出す ──
  it('AC-ALIC1-B07: 委任 FULL（sourceUrl=null）は元ページボタンなし・注記あり', () => {
    const view = render('FULL', { ...full, sourceUrl: null, blogPost: { id: 3, content: { title: '委任', body: '本文' } } })
    expect(view.find('[data-testid="announcement-preview-source"]').exists()).toBe(false)
    const note = view.find('[data-testid="announcement-preview-delegated-note"]')
    expect(note.exists()).toBe(true)
    expect(note.text()).toContain('announcement.preview.delegated_source_note')
  })
  it('AC-ALIC1-B07: 直属 FULL（sourceUrl あり）は元ページボタンあり・注記なし', () => {
    const view = render('FULL', { ...full, blogPost: { id: 3, content: { title: '直属', body: '本文' } } })
    expect(view.find('[data-testid="announcement-preview-source"]').exists()).toBe(true)
    expect(view.find('[data-testid="announcement-preview-delegated-note"]').exists()).toBe(false)
  })
  it.each(['LOCKED', 'ERROR', 'LOADING'] as const)('AC-ALIC1-B07: %s では注記を出さない', (state) => {
    const view = render(state, null)
    expect(view.find('[data-testid="announcement-preview-delegated-note"]').exists()).toBe(false)
  })
})
