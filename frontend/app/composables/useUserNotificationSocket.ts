import { getAuthSessionContext, type AuthSessionSnapshot } from '~/composables/authSessionContext'
import { useNotificationStore } from '~/stores/useNotificationStore'
import { useChatWebSocket } from '~/composables/chat/useChatWebSocket'
import type { WebSocketNotificationPayload } from '~/stores/useNotificationStore'

/**
 * ユーザー宛リアルタイム通知（`/user/queue/notifications`）の購読 composable。
 *
 * `docs/architecture/websocket_external_broker_valkey.md` §2.5・AC-9（隊5）。
 * BE の Principal 配線（同設計書 §2.3・並行隊で対応中）が完成すると、
 * `NotificationDispatchService.sendViaWebSocket` の `convertAndSendToUser` が
 * 本 destination に個別通知を配信するようになる。FE 側には従来この受け口が
 * 存在せず（`useNotificationStore.setLatestNotification` は呼び出し元ゼロ）、
 * 本 composable がその最小結線を担う。
 *
 * ## 動くものと同じパターン（独自実装しない）
 * 新規の WebSocket 接続は開かない。`useChatWebSocket` が保持する**共有シングルトン
 * STOMP 接続**（JWT Bearer 付き CONNECT・beforeConnect でのトークン差し替え・
 * reconnectDelay 指数バックオフ・Visibility API 連動）に相乗りし、`subscribeRaw`
 * （在席等チャット以外の用途向けに既に用意されている汎用購読口）で本トピックを
 * 購読する。`useVillageLobbyPresence`（`app/composables/village/useVillageLobbyPresence.ts`）
 * と全く同じ流儀。
 *
 * ## グローバルに1本（多重接続防止）
 * `start()` は認証世代ごとの共有購読とconsumer所有で冪等化してあり、認証済み
 * セッション中に複数回呼ばれても STOMP SUBSCRIBE は1回のみ実行される
 * （呼び出し元は `app/layouts/default.vue`・認証済みの間だけ・ログアウトで `stop()`）。
 */

interface NotificationLease {
  unsubscribe: (() => void) | null
  owners: number
  session: ReturnType<typeof getAuthSessionContext>
  snapshot: AuthSessionSnapshot
  store: ReturnType<typeof useNotificationStore>
}
let activeLease: NotificationLease | null = null

/** 同じ認証世代の購読だけを共有し、consumerごとに取得・解除を所有する。 */
export function useUserNotificationSocket() {
  const { subscribeRaw } = useChatWebSocket()
  const authStore = useAuthStore()
  const notificationStore = useNotificationStore()
  const session = getAuthSessionContext(authStore)
  let ownedLease: NotificationLease | null = null

  function release(lease: NotificationLease): void {
    if (activeLease === lease) {
      activeLease = null
      lease.store.$reset()
    }
    const unsubscribe = lease.unsubscribe
    lease.unsubscribe = null
    unsubscribe?.()
  }

  function start(): void {
    if (import.meta.server) return
    const snapshot = session.capture()
    if (snapshot.accountId === null) {
      stop()
      return
    }
    if (ownedLease === activeLease && ownedLease !== null && session.current(ownedLease.snapshot))
      return
    stop()
    if (
      activeLease !== null &&
      (activeLease.session !== session || !session.current(activeLease.snapshot))
    ) {
      release(activeLease)
    }
    if (activeLease === null) {
      const lease: NotificationLease = {
        unsubscribe: null,
        owners: 0,
        session,
        snapshot,
        store: notificationStore,
      }
      activeLease = lease
      lease.unsubscribe = subscribeRaw('/user/queue/notifications', (body: string) => {
        if (activeLease !== lease || !session.current(snapshot)) return
        const payload = parseUserNotificationPayload(body)
        if (payload && payload.userId === snapshot.accountId)
          notificationStore.setLatestNotification(payload)
      })
    }
    ownedLease = activeLease
    ownedLease.owners += 1
  }

  function stop(): void {
    const lease = ownedLease
    ownedLease = null
    if (lease === null) return
    lease.owners -= 1
    if (lease.owners === 0) release(lease)
  }

  return { start, stop }
}
/**
 * STOMP 受信本文（JSON 文字列）→ {@link WebSocketNotificationPayload}。
 *
 * <p>前向き境界（any 禁止・型載せ替えはここ 1 箇所）: `NotificationResponse`（生成型）は
 * OpenAPI 契約由来で REST 一覧 API と同一形状。`id` が数値でなければ不正フレームとして null。</p>
 */
export function parseUserNotificationPayload(body: string): WebSocketNotificationPayload | null {
  try {
    const raw: unknown = JSON.parse(body)
    if (typeof raw !== 'object' || raw === null) return null
    const obj = raw as Record<string, unknown>
    if (typeof obj.id !== 'number') return null
    return raw as WebSocketNotificationPayload
  } catch {
    // eslint-disable-next-line no-restricted-syntax -- 壊れた通知フレーム（JSON パース不能）を破棄する防御。null=不正フレームは購読側で無視
    return null
  }
}
