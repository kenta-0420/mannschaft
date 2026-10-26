import type { ChatThreadResponse, ChatActiveThreadItem } from '~/types/chat'
import { buildQuery } from './chatQuery'
import { mapBeThread, type BeThreadResponse } from './chatMessageMapper'

/**
 * チャットスレッド系 API を提供する composable。
 *
 * BE のスレッドレスポンスは root / messages がネスト形状のメッセージで返るため、
 * {@link mapBeThread} で各メッセージを FE フラット型へ変換する（reactionSummary /
 * myReactions 等が欠落して描画クラッシュするのを防ぐ）。
 *
 * 提供する関数:
 * - getThread:        単一スレッドの返信一覧取得（カーソルベース）
 * - getActiveThreads: チャネル内のアクティブスレッド一覧取得
 */
export function useChatThreads() {
  const api = useApi()
  const authStore = useAuthStore()
  const currentUserId = computed(() => authStore.user?.id)

  async function getThread(
    messageId: number,
    cursor?: string,
    limit?: number,
  ): Promise<{ data: ChatThreadResponse }> {
    const qs = buildQuery({ cursor, limit })
    const raw = await api<{ data: BeThreadResponse }>(
      `/api/v1/chat/messages/${messageId}/thread?${qs}`,
    )
    return { data: mapBeThread(raw.data, currentUserId.value) }
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
