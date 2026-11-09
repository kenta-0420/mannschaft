import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import { useChatReactions } from '~/composables/chat/useChatReactions'
import { mapCursorMeta } from '~/composables/chat/chatQuery'

const api = vi.fn()

mockNuxtImport('useApi', () => () => api)

// CMP-261105-1507 棚卸し: FE が BE の実在する窓口・応答形に合わせていること
describe('chat API の BE 窓口整合', () => {
  beforeEach(() => {
    api.mockReset()
  })

  it('リアクション削除は DELETE /reactions?emoji= で送る', async () => {
    api.mockResolvedValue(undefined)
    await useChatReactions().removeReaction(9, '👍')
    expect(api).toHaveBeenCalledWith(
      `/api/v1/chat/messages/9/reactions?emoji=${encodeURIComponent('👍')}`,
      { method: 'DELETE' },
    )
  })

  it('BE の CursorMeta.hasNext を hasMore へ写す（meta 欠落は終端扱い）', () => {
    expect(mapCursorMeta({ nextCursor: 'n', hasNext: true })).toEqual({
      nextCursor: 'n',
      hasMore: true,
    })
    expect(mapCursorMeta(undefined)).toEqual({ nextCursor: null, hasMore: false })
  })
})
