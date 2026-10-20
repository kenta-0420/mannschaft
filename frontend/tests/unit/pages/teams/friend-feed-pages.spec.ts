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
  const exportRows = [
    { forwardId: 1, sourcePostId: 101, forwardingTeamName: 'Friend FC', target: 'MEMBER', forwardedAt: '2026-10-01T00:00:00Z', isRevoked: false },
    { forwardId: 2, sourcePostId: 102, forwardingTeamName: 'Other FC', target: 'MEMBER_AND_SUPPORTER', forwardedAt: '2026-10-02T00:00:00Z', isRevoked: true },
  ]

  it('AC-2: FriendForwardExportList を解決し、履歴の各行（投稿ID・転送先・取消状態）を描画する', async () => {
    apiMock.mockResolvedValue({ data: exportRows, pagination: { totalElements: 2, totalPages: 1, hasNext: false } })
    const wrapper = await mountSuspended(ForwardExportsPage, { route: '/teams/t1/friend-forward-exports' })
    await flushPromises()
    const list = wrapper.findComponent(FriendForwardExportList)
    expect(list.exists()).toBe(true)
    const rows = list.findAll('tbody tr')
    expect(rows).toHaveLength(2)
    expect(rows[0]!.text()).toContain('#101')
    expect(rows[0]!.text()).toContain('Friend FC')
    expect(rows[1]!.text()).toContain('#102')
    expect(rows[1]!.text()).toContain('Other FC')
    // 取消済みの行だけに状態タグが出る
    expect(rows[0]!.find('.p-tag').exists()).toBe(false)
    expect(rows[1]!.find('.p-tag').exists()).toBe(true)
  })

  it('AC-3: 取得失敗時は DashboardErrorState の再試行で再取得し、成功すれば履歴が出る', async () => {
    apiMock
      .mockRejectedValueOnce(new Error('boom'))
      .mockResolvedValueOnce({ data: exportRows, pagination: { totalElements: 2, totalPages: 1, hasNext: false } })
    const wrapper = await mountSuspended(ForwardExportsPage, { route: '/teams/t1/friend-forward-exports' })
    await flushPromises()
    const list = wrapper.findComponent(FriendForwardExportList)
    expect(list.findComponent({ name: 'DashboardErrorState' }).exists()).toBe(true)
    expect(apiMock).toHaveBeenCalledTimes(1)
    await list.find('[data-testid="forward-exports-error-retry"]').trigger('click')
    await flushPromises()
    expect(apiMock).toHaveBeenCalledTimes(2)
    expect(list.find('[data-testid="forward-exports-error"]').exists()).toBe(false)
    expect(list.findAll('tbody tr')).toHaveLength(2)
  })

  it('AC-3: 2ページ目の取得に失敗してもページ送り（前へ）は残り、前ページへ戻れる', async () => {
    const page = { totalElements: 40, totalPages: 2 }
    apiMock
      .mockResolvedValueOnce({ data: exportRows, pagination: { ...page, hasNext: true } })
      .mockRejectedValueOnce(new Error('boom'))
      .mockResolvedValueOnce({ data: exportRows, pagination: { ...page, hasNext: true } })
    const wrapper = await mountSuspended(ForwardExportsPage, { route: '/teams/t1/friend-forward-exports' })
    await flushPromises()
    const list = wrapper.findComponent(FriendForwardExportList)
    const buttons = () => list.findAll('.flex.gap-2 button')
    await buttons()[1]!.trigger('click')
    await flushPromises()
    expect(list.find('[data-testid="forward-exports-error"]').exists()).toBe(true)
    expect(buttons()).toHaveLength(2)
    expect(buttons()[0]!.attributes('disabled')).toBeUndefined()
    expect(buttons()[1]!.attributes('disabled')).toBeDefined()
    await buttons()[0]!.trigger('click')
    await flushPromises()
    expect(list.find('[data-testid="forward-exports-error"]').exists()).toBe(false)
    expect(list.findAll('tbody tr')).toHaveLength(2)
  })

  it('AC-3: 0件のとき空状態の文言を出す', async () => {
    apiMock.mockResolvedValue({ data: [], pagination: { totalElements: 0, totalPages: 0, hasNext: false } })
    const wrapper = await mountSuspended(ForwardExportsPage, { route: '/teams/t1/friend-forward-exports' })
    await flushPromises()
    expect(wrapper.findComponent(FriendForwardExportList).findComponent({ name: 'DashboardEmptyState' }).exists()).toBe(true)
  })

  it('AC-3: 取得失敗時は一覧内に DashboardErrorState を表示する（空状態に偽装しない）', async () => {
    apiMock.mockRejectedValue(new Error('boom'))
    const wrapper = await mountSuspended(ForwardExportsPage, { route: '/teams/t1/friend-forward-exports' })
    await flushPromises()
    const list = wrapper.findComponent(FriendForwardExportList)
    expect(handleApiError).toHaveBeenCalled()
    expect(list.find('[data-testid="forward-exports-error"]').exists()).toBe(true)
    expect(list.findComponent({ name: 'DashboardEmptyState' }).exists()).toBe(false)
  })
})
