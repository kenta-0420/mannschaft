import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import { useFriendFeed } from './useFriendFeed'
import { useFriendForwardApi } from './useFriendForwardApi'

const { api, handleApiError } = vi.hoisted(() => ({ api: vi.fn(), handleApiError: vi.fn() }))
mockNuxtImport('useApi', () => () => api)
mockNuxtImport('useErrorHandler', () => () => ({ handleApiError }))

/** UIの存在しない続きを読む・転送取消はcomposableの現契約だけを固定する。 */
describe('CMP-261014-2035 K1 フィード・転送取消の契約', () => {
  beforeEach(() => { api.mockReset(); handleApiError.mockReset() })
  it('cursor=10&limit=20で取得しnextCursorとhasNextを保存する', async () => {
    const meta = { nextCursor: 30, limit: 20, hasNext: true }
    api.mockResolvedValue({ data: [], meta })
    const feed = useFriendFeed('t')
    await feed.fetchFeed({ cursor: 10, limit: 20 })
    expect(api).toHaveBeenCalledExactlyOnceWith('/api/v1/teams/t/friend-feed?cursor=10&limit=20')
    expect(feed.meta.value).toEqual(meta)
    expect(feed.posts.value).toEqual([])
    expect(feed.failed.value).toBe(false)
    expect(feed.loading.value).toBe(false)
  })
  it('取得失敗は空成功へ置き換えずfailedと元のエラーを保持する', async () => {
    const failure = new Error('ネットワーク断')
    api.mockRejectedValue(failure)
    const feed = useFriendFeed('t')
    await feed.fetchFeed({ cursor: 10, limit: 20 })
    expect(feed.failed.value).toBe(true)
    expect(feed.error.value).toBe(failure)
    expect(feed.loading.value).toBe(false)
    expect(handleApiError).toHaveBeenCalledExactlyOnceWith(failure, 'friend-feed')
  })
  it('revokeForwardはforwardIdを指定した正確なURLへDELETEする', async () => {
    api.mockResolvedValue(undefined)
    await useFriendForwardApi().revokeForward('t', 7)
    expect(api).toHaveBeenCalledExactlyOnceWith('/api/v1/teams/t/friend-feed/forwards/7', { method: 'DELETE' })
  })
})
