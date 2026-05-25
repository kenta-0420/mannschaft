import { useChatWebSocket } from '../chat/useChatWebSocket'
import type { LobbyPresenceResponse, PresenceMember } from '~/types/village'

/**
 * 村ロビー在席インジケーター composable（F17.1 Phase 2）。
 *
 * - start(): REST で初期在席一覧を取得し、STOMP 購読 + join 送信 + 30秒ハートビート開始
 * - stop():  leave 送信 + ハートビート停止 + 購読解除
 */
export function useVillageLobbyPresence(villageId: string) {
  const api = useApi()
  const { subscribeRaw, publishRaw } = useChatWebSocket()

  const members = ref<PresenceMember[]>([])
  const activeCount = computed(() => members.value.length)

  let heartbeatTimer: ReturnType<typeof setInterval> | null = null
  let _unsubscribe: (() => void) | null = null
  let _started = false

  async function fetchPresence(): Promise<void> {
    try {
      const res = await api<LobbyPresenceResponse>(
        `/api/v1/villages/${villageId}/lobby/presence`,
      )
      members.value = res.members ?? []
    }
    catch {
      // 在席取得失敗はサイレント（STOMP 受信で補完される）
    }
  }

  function join(): void {
    publishRaw(`/app/villages/${villageId}/lobby/presence/join`)
    heartbeatTimer = setInterval(() => {
      publishRaw(`/app/villages/${villageId}/lobby/presence/heartbeat`)
    }, 30_000)
  }

  function leave(): void {
    publishRaw(`/app/villages/${villageId}/lobby/presence/leave`)
    if (heartbeatTimer !== null) {
      clearInterval(heartbeatTimer)
      heartbeatTimer = null
    }
  }

  function start(): void {
    if (_started) return
    _started = true
    fetchPresence()
    _unsubscribe = subscribeRaw(
      `/topic/villages/${villageId}/lobby/presence`,
      (body: string) => {
        try {
          const data = JSON.parse(body) as LobbyPresenceResponse
          members.value = data.members ?? []
        }
        catch {
          // パース失敗は無視
        }
      },
    )
    join()
  }

  function stop(): void {
    leave()
    _unsubscribe?.()
    _unsubscribe = null
    _started = false
  }

  return { members, activeCount, start, stop }
}
