import type { ApiResponse } from '~/types/api'
import type {
  AnnouncementScopeType,
} from '~/types/announcement'
import type {
  BroadcastRequest,
  BroadcastResponse,
} from '~/types/announcement_broadcast'

/**
 * F02.8 告知ウィザード broadcast composable。
 *
 * POST /api/v1/{scopeType}/{scopeId}/broadcast を呼び出す。
 *
 * @param scopeType スコープ種別（TEAM / ORGANIZATION）
 * @param scopeId   スコープ ID
 */
export function useAnnouncementBroadcast(scopeType: AnnouncementScopeType, scopeId: string) {
  const api = useApi()
  const broadcasting = ref(false)
  const broadcastError = ref<string | null>(null)

  function broadcastPath() {
    const scope = scopeType === 'TEAM' ? 'teams' : 'organizations'
    return `/api/v1/${scope}/${scopeId}/broadcast`
  }

  /**
   * 告知を送信する。
   * @param request 告知リクエスト
   * @returns 作成された告知フィード情報
   */
  async function broadcast(request: BroadcastRequest): Promise<BroadcastResponse> {
    broadcasting.value = true
    broadcastError.value = null
    try {
      const res = await api<ApiResponse<BroadcastResponse>>(broadcastPath(), {
        method: 'POST',
        body: request,
      })
      return res.data
    }
    catch {
      broadcastError.value = '告知の送信に失敗しました'
      throw broadcastError.value
    }
    finally {
      broadcasting.value = false
    }
  }

  return {
    broadcasting,
    broadcastError,
    broadcast,
  }
}
