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
  const { t } = useI18n()
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
    catch (e: unknown) {
      // BE の真因（error.code / error.message）を握り潰さず broadcastError に反映する。
      // 表示の既定文言は i18n キー。BE から具体的メッセージ・コードが返れば
      // それをそのまま添えてデバッグ可能にする（汎用文言での上書き＝症状隠しを禁止）。
      const detail = (e as { data?: { error?: { code?: string; message?: string } } })?.data?.error
      const beMessage = detail?.message
      const beCode = detail?.code
      if (beMessage) {
        broadcastError.value = beCode ? `${beMessage} (${beCode})` : beMessage
      }
      else if (beCode) {
        broadcastError.value = `${t('announcement.broadcast_error_generic')} (${beCode})`
      }
      else {
        broadcastError.value = t('announcement.broadcast_error_generic')
      }
      // 原因（元の例外）を保持したまま再 throw する。
      throw e
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
