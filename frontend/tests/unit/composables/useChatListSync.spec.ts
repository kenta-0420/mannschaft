import { describe, expect, it, vi } from 'vitest'
import { useChatListSync } from '~/composables/chat/useChatListSync'

describe('useChatListSync', () => {
  it('チャンネルの内容変更（編集・削除・投稿）を購読者へ通知する', () => {
    const handler = vi.fn()
    const { onChannelContentChanged, notifyChannelContentChanged } = useChatListSync()
    const off = onChannelContentChanged(handler)
    notifyChannelContentChanged(7)
    // vueuse のバスはハンドラへ (event, payload) を渡すため、第1引数だけを見る
    expect(handler.mock.calls[0]?.[0]).toBe(7)
    off()
    notifyChannelContentChanged(8)
    expect(handler).toHaveBeenCalledTimes(1)
  })
})
