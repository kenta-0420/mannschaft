import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import TimelinePostDetailPage from './[postId].vue'

const mocks = vi.hoisted(() => ({ getPost: vi.fn(), showError: vi.fn() }))
mockNuxtImport('useRoute', () => () => ({ params: { postId: '99' } }))
mockNuxtImport('useI18n', () => () => ({ t: (key: string) => key }))
mockNuxtImport('useTimelineApi', () => () => ({
  getPost: mocks.getPost,
  createReply: vi.fn(),
  getReplies: vi.fn(),
  addBookmark: vi.fn(),
  removeBookmark: vi.fn(),
}))
mockNuxtImport('useNotification', () => () => ({ showSuccess: vi.fn(), showError: mocks.showError }))

const options = {
  global: {
    stubs: {
      PageLoading: { template: '<div data-testid="page-loading" />' },
      TimelinePostCard: { props: ['post'], template: '<article data-testid="post-card">{{ post.body }}</article>' },
      Button: { props: ['label'], template: '<button data-testid="btn">{{ label }}</button>' },
      Textarea: true,
    },
  },
}

describe('投稿詳細ページの取得結果', () => {
  beforeEach(() => vi.clearAllMocks())

  it('取得失敗(404相当)では読み込み中のまま止まらず、見つからない表示と戻る導線を出す', async () => {
    mocks.getPost.mockRejectedValue({ status: 404 })
    const wrapper = await mountSuspended(TimelinePostDetailPage, options)
    await flushPromises()
    expect(wrapper.find('[data-testid="page-loading"]').exists()).toBe(false)
    const notFound = wrapper.find('[data-testid="timeline-post-not-found"]')
    expect(notFound.exists()).toBe(true)
    expect(notFound.text()).toContain('timeline.detail.notFound')
    expect(wrapper.text()).toContain('timeline.detail.back')
    expect(wrapper.find('[data-testid="post-card"]').exists()).toBe(false)
  })

  it('正常取得時は投稿を描画する', async () => {
    mocks.getPost.mockResolvedValue({ data: { id: 99, body: 'hello post', recentReplies: [], stats: { replyCount: 0 } } })
    const wrapper = await mountSuspended(TimelinePostDetailPage, options)
    await flushPromises()
    expect(wrapper.find('[data-testid="page-loading"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="post-card"]').text()).toContain('hello post')
    expect(wrapper.find('[data-testid="timeline-post-not-found"]').exists()).toBe(false)
  })

  it.each([
    { back: null, expectPush: true },
    { back: '/timeline', expectPush: false },
  ])('戻る: history.state.back=$back のとき（アプリ内履歴が無ければ一覧へ push、あれば router.back）', async ({ back, expectPush }) => {
    mocks.getPost.mockRejectedValue({ status: 404 })
    const router = useRouter()
    const push = vi.spyOn(router, 'push').mockResolvedValue(undefined)
    const goBack = vi.spyOn(router, 'back').mockImplementation(() => {})
    window.history.replaceState({ ...window.history.state, back }, '')
    const wrapper = await mountSuspended(TimelinePostDetailPage, options)
    await flushPromises()
    await wrapper.find('[data-testid="btn"]').trigger('click')
    if (expectPush) {
      expect(push).toHaveBeenCalledWith('/timeline')
      expect(goBack).not.toHaveBeenCalled()
    } else {
      expect(goBack).toHaveBeenCalled()
      expect(push).not.toHaveBeenCalledWith('/timeline')
    }
  })
})
