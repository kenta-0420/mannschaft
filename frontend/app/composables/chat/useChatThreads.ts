import type { ChatThreadResponse, ChatActiveThreadItem } from '~/types/chat'
import { buildQuery } from './chatQuery'

/**
 * チャットスレッド系 API を提供する composable。
 *
 * 提供する関数:
 * - getThread:        単一スレッドの返信一覧取得（カーソルベース）
 * - getActiveThreads: チャネル内のアクティブスレッド一覧取得
 */
export function useChatThreads() {
  const api = useApi()

  async function getThread(messageId: number, cursor?: string, limit?: number) {
    const qs = buildQuery({ cursor, limit })
    return api<{ data: ChatThreadResponse }>(`/api/v1/chat/messages/${messageId}/thread?${qs}`)
  }

  async function getActiveThreads(channelId: number, cursor?: string, limit?: number) {
    const qs = buildQuery({ cursor, limit })
    return api<{
      data: ChatActiveThreadItem[]
      meta: { nextCursor: string | null; hasMore: boolean }
    }>(`/api/v1/chat/channels/${channelId}/active-threads?${qs}`)
  }

  return {
    getThread,
    getActiveThreads,
  }
}
