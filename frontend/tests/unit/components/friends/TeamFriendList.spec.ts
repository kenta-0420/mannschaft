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
const api = vi.hoisted(() => ({ listFriends: vi.fn() }))

mockNuxtImport('useFriendTeamsApi', () => () => ({
  listFriends: api.listFriends,
  unfollow: vi.fn(),
}))
mockNuxtImport('useErrorHandler', () => () => ({ handleApiError: vi.fn() }))

const props = { teamId: 'team-1', canEdit: false, canToggleVisibility: false }
const stubs = { TeamFriendCard: true }

async function mountList() {
  const w = await mountSuspended(TeamFriendList, { props, global: { stubs } })
  await flushPromises()
  return w
}

describe('components/friends/TeamFriendList.vue — 取得失敗の表示', () => {
  beforeEach(() => api.listFriends.mockReset())

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
})
