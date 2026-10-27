import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import TeamFriendList from '~/components/friends/TeamFriendList.vue'

/**
 * CMP-261013-1226: フレンド一覧の取得失敗を空状態と混同しない。
 *   AC-14 403 → 権限なし案内(forbidden)
 *   AC-15 5xx・通信断 → エラー表示＋再試行で再取得
 *   AC-17 0件の正規メンバー → 従来どおり空状態
 */
const api = vi.hoisted(() => ({ listFriends: vi.fn(), unfollow: vi.fn(), confirm: vi.fn() }))

mockNuxtImport('useFriendTeamsApi', () => () => ({
  listFriends: api.listFriends,
  unfollow: api.unfollow,
}))
mockNuxtImport('useConfirmDialog', () => () => ({ confirmAction: api.confirm }))
mockNuxtImport('useErrorHandler', () => () => ({ handleApiError: vi.fn() }))

const props = { teamId: 'team-1', canEdit: false, canToggleVisibility: false }
const stubs = { TeamFriendCard: true }

async function mountList() {
  const w = await mountSuspended(TeamFriendList, { props, global: { stubs } })
  await flushPromises()
  return w
}

describe('components/friends/TeamFriendList.vue — 取得失敗の表示', () => {
  // 波括弧必須: アロー式で mock を返すと vitest が後始末関数として呼び出し、拒否値が漏れる
  beforeEach(() => {
    api.listFriends.mockReset()
    api.unfollow.mockReset()
    api.confirm.mockClear()
  })

  it('AC-14: 403 は空状態ではなく forbidden 案内を出す', async () => {
    api.listFriends.mockRejectedValue({ statusCode: 403 })
    const w = await mountList()
    expect(w.find('[data-testid="friend-list-error"]').exists()).toBe(true)
    expect(w.text()).not.toContain('フレンドチームがありません')
    expect(w.find('[data-testid="friend-list-error"] .pi-lock').exists()).toBe(true)
    expect(w.find('[data-testid="friend-list-error-retry"]').exists()).toBe(false)
  })

  it('AC-15: 500 はエラー表示＋再試行ボタン。再試行で再取得する', async () => {
    api.listFriends.mockRejectedValueOnce({ statusCode: 500 })
    api.listFriends.mockResolvedValueOnce({ data: [], pagination: { totalElements: 0 } })
    const w = await mountList()
    expect(w.find('[data-testid="friend-list-error"]').exists()).toBe(true)
    expect(w.find('[data-testid="friend-list-empty"]').exists()).toBe(false)
    const retry = w.find('[data-testid="friend-list-error-retry"]')
    expect(retry.exists()).toBe(true)
    await retry.trigger('click')
    await flushPromises()
    expect(api.listFriends).toHaveBeenCalledTimes(2)
    expect(w.find('[data-testid="friend-list-error"]').exists()).toBe(false)
  })

  it('AC-15: 通信断(FetchError)もエラー表示', async () => {
    api.listFriends.mockRejectedValue(Object.assign(new Error('Failed to fetch'), { name: 'FetchError' }))
    const w = await mountList()
    expect(w.find('[data-testid="friend-list-error"]').exists()).toBe(true)
    expect(w.find('[data-testid="friend-list-empty"]').exists()).toBe(false)
  })

  it('AC-17: 0件の正規メンバーは従来どおり空状態（エラーを出さない）', async () => {
    api.listFriends.mockResolvedValue({ data: [], pagination: { totalElements: 0 } })
    const w = await mountList()
    expect(w.find('[data-testid="friend-list-empty"]').exists()).toBe(true)
    expect(w.find('[data-testid="friend-list-error"]').exists()).toBe(false)
  })
  it('AC16: 既存一覧も空ページを最終有効ページへ一度だけ補正しfirstを同期する', async () => {
    const data = [{ teamFriendId: 1, friendTeamId: 2, friendTeamName: '相手', public: true, establishedAt: '2026-01-01' }]
    api.listFriends.mockResolvedValueOnce({ data, pagination: { totalElements: 41, totalPages: 3 } })
      .mockResolvedValueOnce({ data: [], pagination: { totalElements: 21, totalPages: 2 } })
      .mockResolvedValueOnce({ data, pagination: { totalElements: 21, totalPages: 2 } })
    const w = await mountList()
    w.findComponent({ name: 'Paginator' }).vm.$emit('page', { page: 5 })
    await flushPromises()
    expect(api.listFriends.mock.calls.map(c => c[1].page)).toEqual([0, 5, 1])
    expect(w.findComponent({ name: 'Paginator' }).props('first')).toBe(20)
  })

  it.each([false, true])('AC16/18: 最終行の取消または取消404後に空ページを補正し相手一覧の同期を要求する（404=%s）', async (missing) => {
    const friend = { teamFriendId: 1, friendTeamId: 2, friendTeamName: '相手', public: true, establishedAt: '2026-01-01' }
    const result = (data: typeof friend[], total: number) => ({ data, pagination: { totalElements: total, totalPages: Math.ceil(total / 20) } })
    api.listFriends.mockResolvedValueOnce(result([friend], 21)).mockResolvedValueOnce(result([friend], 21))
      .mockResolvedValueOnce(result([], 20)).mockResolvedValueOnce(result([friend], 20))
    if (missing) api.unfollow.mockRejectedValueOnce({ statusCode: 404, data: { error: { code: 'SOCIAL_103' } } })
    else api.unfollow.mockResolvedValueOnce(undefined)
    const w = await mountList()
    w.findComponent({ name: 'Paginator' }).vm.$emit('page', { page: 1 })
    await flushPromises()
    w.findComponent({ name: 'TeamFriendCard' }).vm.$emit('unfollow', friend)
    await api.confirm.mock.calls[0]![0].onAccept()
    await flushPromises()
    expect(api.listFriends.mock.calls.map(c => c[1].page)).toEqual([0, 1, 1, 0])
    expect(w.emitted('refresh')).toHaveLength(1)
  })

  it('AC16: 取得失敗では補正せず同じページで再試行する', async () => {
    const data = [{ teamFriendId: 1, friendTeamId: 2 }]
    api.listFriends.mockResolvedValueOnce({ data, pagination: { totalElements: 41, totalPages: 3 } })
      .mockRejectedValueOnce({ statusCode: 500 })
      .mockResolvedValueOnce({ data: [], pagination: { totalElements: 0, totalPages: 0 } })
    const w = await mountList()
    w.findComponent({ name: 'Paginator' }).vm.$emit('page', { page: 2 })
    await flushPromises()
    expect(api.listFriends).toHaveBeenCalledTimes(2)
    await w.get('[data-testid="friend-list-error-retry"]').trigger('click')
    await flushPromises()
    expect(api.listFriends.mock.calls.map(c => c[1].page)).toEqual([0, 2, 2])
    await (w.vm as unknown as { refresh: () => Promise<void> }).refresh()
    expect(api.listFriends.mock.calls.at(-1)![1].page).toBe(0)
  })

})
