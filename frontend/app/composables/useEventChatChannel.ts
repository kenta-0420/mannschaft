import type { EventChatChannelResponse } from '~/types/event'

/**
 * イベントのチャットチャンネル情報を取得する composable。
 *
 * GET /api/v1/events/{eventId}/channel のラッパー。
 * チャンネルが存在しない場合（HTTP 404）は null を返す。
 */
export function useEventChatChannel() {
  const api = useApi()

  /**
   * 指定イベントに紐づくチャットチャンネルを取得する。
   *
   * @param eventId - イベントID
   * @returns チャンネル情報、またはチャンネルが存在しない場合は null
   */
  async function getEventChannel(eventId: number | string): Promise<EventChatChannelResponse | null> {
    try {
      const res = await api<{ data: EventChatChannelResponse }>(`/api/v1/events/${eventId}/channel`)
      return res.data
    } catch (err: unknown) {
      // 404 は「チャンネル未生成」を意味するため null を返す（エラーは出さない）
      if (
        err !== null &&
        typeof err === 'object' &&
        'status' in err &&
        (err as { status: number }).status === 404
      ) {
        return null
      }
      throw err
    }
  }

  return { getEventChannel }
}
