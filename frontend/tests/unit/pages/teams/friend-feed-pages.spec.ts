/**
 * CMP-261014-2034: フレンド管理者フィード／逆転送履歴ページの本文描画。
 *
 * pathPrefix:false 下でディレクトリ接頭辞付きの名前（FriendsFriendFeedPostList 等）を参照すると
 * Vue が解決できず本文が空白になる。実在コンポーネントを「スタブせずに」描画して検証する。
 * AC-1: friend-feed が FriendFeedPostList / FriendForwardModal を描画する
 * AC-2: friend-forward-exports が FriendForwardExportList を描画する
 * AC-3: 0件で空状態の文言、取得失敗でエラー表示（無言の空白にしない）
 */
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import FriendFeedPage from '~/pages/teams/[slug]/friend-feed.vue'
import ForwardExportsPage from '~/pages/teams/[slug]/friend-forward-exports.vue'
import FriendFeedPostList from '~/components/friends/FriendFeedPostList.vue'
import FriendFeedPostCard from '~/components/friends/FriendFeedPostCard.vue'
import FriendForwardModal from '~/components/friends/FriendForwardModal.vue'
import FriendForwardExportList from '~/components/friends/FriendForwardExportList.vue'

const apiMock = vi.fn()
const handleApiError = vi.fn()

mockNuxtImport('useApi', () => () => apiMock)
mockNuxtImport('useErrorHandler', () => () => ({ handleApiError }))
mockNuxtImport('useRoleAccess', () => () => ({
  isAdmin: ref(true),
  can: () => true,
  loadPermissions: async () => {},
}))

const feedPost = {
  postId: 1,
  sourceTeam: { id: 9, name: 'Friend FC' },
  content: 'フレンドからのお知らせ本文',
  receivedAt: '2026-10-01T00:00:00Z',
  forwardStatus: { isForwarded: false, forwardId: null, forwardedAt: null },
}

beforeEach(() => {
  apiMock.mockReset()
  handleApiError.mockReset()
})

describe('friend-feed ページ', () => {
  it('AC-1: FriendFeedPostList と FriendForwardModal を解決して描画する', async () => {
    apiMock.mockResolvedValue({ data: [], meta: { nextCursor: null, limit: 20, hasNext: false } })
    const wrapper = await mountSuspended(FriendFeedPage, { route: '/teams/t1/friend-feed' })
    await flushPromises()
    expect(wrapper.findComponent(FriendFeedPostList).exists()).toBe(true)
    expect(wrapper.findComponent(FriendForwardModal).exists()).toBe(true)
  })

  it('AC-1: 投稿があれば投稿カードまで描画される', async () => {
    apiMock.mockResolvedValue({ data: [feedPost], meta: { nextCursor: null, limit: 20, hasNext: false } })
    const wrapper = await mountSuspended(FriendFeedPage, { route: '/teams/t1/friend-feed' })
    await flushPromises()
    expect(wrapper.findComponent(FriendFeedPostCard).exists()).toBe(true)
    expect(wrapper.text()).toContain('フレンドからのお知らせ本文')
  })

  it('AC-3: 0件のとき空状態の文言を出す', async () => {
    apiMock.mockResolvedValue({ data: [], meta: { nextCursor: null, limit: 20, hasNext: false } })
    const wrapper = await mountSuspended(FriendFeedPage, { route: '/teams/t1/friend-feed' })
    await flushPromises()
    const text = wrapper.text()
    expect(text).not.toContain('friend_feed.list.empty')
    expect(wrapper.findComponent({ name: 'DashboardEmptyState' }).exists()).toBe(true)
  })

  it('AC-3: 取得失敗時はエラーを表示する', async () => {
    apiMock.mockRejectedValue(new Error('boom'))
    const wrapper = await mountSuspended(FriendFeedPage, { route: '/teams/t1/friend-feed' })
    await flushPromises()
    expect(wrapper.findComponent(FriendFeedPostList).find('.pi-exclamation-triangle').exists()).toBe(true)
  })
})

describe('friend-forward-exports ページ', () => {
  it('AC-2: FriendForwardExportList を解決して描画する', async () => {
    apiMock.mockResolvedValue({ data: [], pagination: { totalElements: 0, totalPages: 0, hasNext: false } })
    const wrapper = await mountSuspended(ForwardExportsPage, { route: '/teams/t1/friend-forward-exports' })
    await flushPromises()
    expect(wrapper.findComponent(FriendForwardExportList).exists()).toBe(true)
  })

  it('AC-3: 0件のとき空状態の文言を出す', async () => {
    apiMock.mockResolvedValue({ data: [], pagination: { totalElements: 0, totalPages: 0, hasNext: false } })
    const wrapper = await mountSuspended(ForwardExportsPage, { route: '/teams/t1/friend-forward-exports' })
    await flushPromises()
    expect(wrapper.findComponent(FriendForwardExportList).findComponent({ name: 'DashboardEmptyState' }).exists()).toBe(true)
  })

  it('AC-3: 取得失敗時は一覧内にエラーを表示する（空状態に偽装しない）', async () => {
    apiMock.mockRejectedValue(new Error('boom'))
    const wrapper = await mountSuspended(ForwardExportsPage, { route: '/teams/t1/friend-forward-exports' })
    await flushPromises()
    const list = wrapper.findComponent(FriendForwardExportList)
    expect(handleApiError).toHaveBeenCalled()
    expect(list.find('[data-testid="forward-exports-error"]').exists()).toBe(true)
    expect(list.findComponent({ name: 'DashboardEmptyState' }).exists()).toBe(false)
  })
})
