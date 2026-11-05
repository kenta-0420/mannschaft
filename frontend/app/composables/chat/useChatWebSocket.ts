import type { Client, IFrame, StompSubscription } from '@stomp/stompjs'
import { Client as StompClient } from '@stomp/stompjs'
import { useEventBus } from '@vueuse/core'
import { computed, readonly, ref } from 'vue'
import {
  buildStompConnectHeaders,
  handleStompErrorFrame,
  useStompAuthRecovery,
  type StompAuthRecovery,
} from '~/composables/stomp/stompAuth'
import { useWsUrl } from '~/composables/useWsUrl'
import type { ChatChannelEvent } from '~/types/chat'
import type { BeMessageListResponse } from './chatMessageMapper'

// ============================================================
// モジュールレベルのシングルトン状態（composable再呼び出しを跨いで維持）
// ============================================================

/**
 * 共有接続上の 1 購読の定義（接続をまたいで保持する）。
 * 接続が切れて再接続したとき、ここに残っている購読をすべて張り直す。
 */
interface StompRoute {
  destination: string
  onFrame: (frame: IFrame) => void
}

/** 購読キー（`msg:{channelId}` / `evt:{channelId}` / `raw:{連番}`）→ 購読定義。 */
const _routes = new Map<string, StompRoute>()
/** 購読キー → 現在の接続上の STOMP Subscription（接続が切れたら無効になる）。 */
const _activeSubscriptions = new Map<string, StompSubscription>()
/**
 * SUBSCRIBE に付けた receipt → 宛先。ERROR フレームの receipt-id から拒否された宛先を特定する。
 * 寿命は「現在の接続上の購読」と同じ: 購読の張り直し・解除・拒否で該当分を消し、接続が切れたら全消去する
 * （onStompError は onWebSocketClose より先に呼ばれるため、切断時の全消去で特定に失敗することはない）。
 */
const _receiptToDestination = new Map<string, string>()
/** 購読キー → 現在の接続上の SUBSCRIBE に付けた receipt。 */
const _receiptByKey = new Map<string, string>()
let _receiptSeq = 0
let _rawSeq = 0

const _subscriptionCounts = new Map<number, number>()
const _eventSubscriptionCounts = new Map<number, number>()
let _stompClient: Client | null = null
/** 各チャンネルで最後に受信した MESSAGE_CREATED の messageId（キャッチアップ用） */
const _lastMessageIdByChannel = new Map<number, number>()
/** 指数バックオフの試行回数 */
let _reconnectAttempts = 0
/** 5回以上連続で再接続失敗した場合に true になるフラグ */
const _wsConnectionFailed = ref(false)
/** 初回接続かどうか（再接続後のキャッチアップ判定用） */
let _isFirstConnect = true
/** 接続待ち（sendTyping / publishRaw 用）。onConnect で解決する。 */
let _connectWaiters: Array<{ resolve: () => void; reject: (error: Error) => void }> = []

/** 購読を拒否された宛先（FORBIDDEN / INVALID_DESTINATION）。再接続時に張り直さない。 */
const _deniedDestinations = ref<string[]>([])
/** 共有接続の認証状態。UNAUTHENTICATED はトークン更新でも回復しなかった（再接続を止めている）。 */
const _wsAuthState = ref<'OK' | 'UNAUTHENTICATED'>('OK')
/** UNAUTHENTICATED の回復手順（最初の composable 呼び出し＝setup コンテキストで取得）。 */
let _authRecovery: StompAuthRecovery | null = null
let _authStore: ReturnType<typeof useAuthStore> | null = null
/** 進行中の UNAUTHENTICATED 回復。beforeConnect で完了を待ってから再接続する。 */
let _pendingAuthRecovery: Promise<void> | null = null
/** キャッチアップ用 API（最初の composable 呼び出し＝setup コンテキストで取得）。 */
let _catchupApi: ReturnType<typeof useApi> | null = null

/** チャンネルのメッセージトピック。 */
function channelDestination(channelId: number): string {
  return `/topic/channels/${channelId}`
}

/** チャンネルのイベントトピック。 */
function channelEventsDestination(channelId: number): string {
  return `/topic/channels/${channelId}/events`
}

/** 購読キーの現在の接続上の購読と receipt を外す（UNSUBSCRIBE は呼び出し側の判断で行う）。 */
function _forgetActive(key: string, unsubscribe: boolean): void {
  if (unsubscribe) _activeSubscriptions.get(key)?.unsubscribe()
  _activeSubscriptions.delete(key)
  const receipt = _receiptByKey.get(key)
  if (receipt !== undefined) {
    _receiptToDestination.delete(receipt)
    _receiptByKey.delete(key)
  }
}

/** 接続が切れたときに、その接続上の購読と receipt をすべて捨てる。 */
function _forgetAllActive(): void {
  _activeSubscriptions.clear()
  _receiptToDestination.clear()
  _receiptByKey.clear()
}

/** 現在の接続上で 1 購読を張る（拒否済みの宛先・未接続なら何もしない）。張り直しでも二重にはならない。 */
function _subscribeRoute(key: string): void {
  const route = _routes.get(key)
  if (!route || _stompClient === null || !_stompClient.connected) return
  if (_deniedDestinations.value.includes(route.destination)) return
  _forgetActive(key, true)
  // receipt を付けておくと、SUBSCRIBE が拒否されたとき ERROR フレームの receipt-id に同じ値が載る
  // （Spring の StompSubProtocolErrorHandler の既定動作）。これで共有接続でも拒否された宛先を特定できる。
  const receipt = `sub-${++_receiptSeq}`
  _receiptToDestination.set(receipt, route.destination)
  _receiptByKey.set(key, receipt)
  const subscription = _stompClient.subscribe(route.destination, route.onFrame, { receipt })
  _activeSubscriptions.set(key, subscription)
}

/** テスト・診断用: 共有接続が保持している購読・receipt の件数（増え続けないことの確認に使う）。 */
export function getChatWsDiagnostics(): { routes: number; activeSubscriptions: number; receipts: number } {
  return {
    routes: _routes.size,
    activeSubscriptions: _activeSubscriptions.size,
    receipts: _receiptToDestination.size,
  }
}

/** 購読を登録し、接続済みなら即座に張る（未接続なら接続後に onConnect が張る）。 */
function _registerRoute(key: string, route: StompRoute): void {
  _routes.set(key, route)
  // 明示的な購読開始は再試行とみなす（メンバーに追加された後の再訪など）。
  _deniedDestinations.value = _deniedDestinations.value.filter(d => d !== route.destination)
  if (_stompClient !== null && _stompClient.connected) {
    _subscribeRoute(key)
  }
  else {
    _ensureClientActive()
  }
}

/** 購読を登録から外し、現在の接続上の購読も解除する。 */
function _unregisterRoute(key: string): void {
  _routes.delete(key)
  _forgetActive(key, true)
}

/**
 * 拒否された宛先を記録し、その宛先の購読だけを外す。
 * ERROR 後にサーバが接続を閉じるが、stompjs が自動再接続し、onConnect が残りの購読
 * （ユーザー宛通知・他チャンネル）を張り直すため、拒否された宛先以外は止まらない。
 */
function _denyDestination(destination: string): void {
  // チャンネルの認可はメンバーシップ単位のため、メッセージとイベントの両トピックをまとめて外す
  // （片方ずつ拒否されて再接続を 2 回繰り返さないため）。
  const channelMatch = /^\/topic\/channels\/(\d+)(?:\/events)?$/.exec(destination)
  const targets = channelMatch
    ? [channelDestination(Number(channelMatch[1])), channelEventsDestination(Number(channelMatch[1]))]
    : [destination]
  const merged = new Set([..._deniedDestinations.value, ...targets])
  _deniedDestinations.value = [...merged]
  for (const [key, route] of _routes) {
    if (targets.includes(route.destination)) {
      // ERROR 後にサーバが接続を閉じるため UNSUBSCRIBE は送らない（閉じかけの接続へ送ると stompjs が例外を投げる）。
      _forgetActive(key, false)
    }
  }
}

function _rejectWaiters(error: Error): void {
  const waiters = _connectWaiters
  _connectWaiters = []
  for (const waiter of waiters) waiter.reject(error)
}

/** 共有 STOMP クライアントを生成・活性化する（既に活性なら何もしない）。 */
function _ensureClientActive(): void {
  if (_stompClient !== null) {
    if (!_stompClient.active && _stompClient.connected === false) {
      // 未認証で止めていた接続を、新たな購読・送信の要求を機に再開する（再ログイン後など）。
      _wsAuthState.value = 'OK'
      _stompClient.activate()
    }
    return
  }
  const client = new StompClient({
    webSocketFactory: () => new WebSocket(useWsUrl()),
    // トークンが無い（リロード直後）ときは Authorization を付けず Cookie 認証に任せる（空 Bearer 禁止）。
    connectHeaders: buildStompConnectHeaders(_authStore?.accessToken),
    beforeConnect: async () => {
      // UNAUTHENTICATED の回復（トークン更新）中なら完了を待つ。回復不能なら deactivate 済みで再接続しない。
      if (_pendingAuthRecovery !== null) await _pendingAuthRecovery
      // 再接続時に最新トークンを差し替える（リフレッシュ後の再接続でも有効なトークンを使う）
      if (_stompClient !== null) {
        _stompClient.connectHeaders = buildStompConnectHeaders(_authStore?.accessToken)
      }
    },
    reconnectDelay: 1000,
    onConnect: () => {
      _wsAuthState.value = 'OK'
      // 登録済みの購読をすべて張る（初回・再接続とも。拒否済みの宛先は除く）。
      for (const key of _routes.keys()) {
        _subscribeRoute(key)
      }
      if (!_isFirstConnect) {
        // 切断中に届いたメッセージをキャッチアップ
        for (const [channelId, lastId] of _lastMessageIdByChannel) {
          void _catchupMessages(channelId, lastId)
        }
      }
      _isFirstConnect = false
      _reconnectAttempts = 0
      _wsConnectionFailed.value = false
      const waiters = _connectWaiters
      _connectWaiters = []
      for (const waiter of waiters) waiter.resolve()
    },
    onWebSocketClose: () => {
      // 接続が切れたら現在の購読と receipt はすべて無効。再接続時に onConnect が _routes から張り直す。
      _forgetAllActive()
    },
    onDisconnect: () => {
      // 指数バックオフ: 1s → 2s → 4s → 8s → 16s → 最大30s
      const delays = [1000, 2000, 4000, 8000, 16000, 30000]
      _reconnectAttempts = Math.min(_reconnectAttempts + 1, delays.length - 1)
      if (_stompClient !== null) {
        _stompClient.reconnectDelay = delays[_reconnectAttempts] ?? 30000
      }
      if (_reconnectAttempts >= 5) {
        _wsConnectionFailed.value = true
      }
    },
    onStompError: (frame: IFrame) => {
      const receiptId = frame.headers['receipt-id']
      const destination = receiptId ? _receiptToDestination.get(receiptId) : undefined
      if (_authRecovery === null) {
        _rejectWaiters(new Error(`STOMP エラー: ${frame.headers['message'] ?? 'unknown'}`))
        return
      }
      const recovery = handleStompErrorFrame(frame, _authRecovery, {
        onDenied: () => {
          if (destination !== undefined) {
            // 拒否された宛先だけを外し、通知購読など他の購読は再接続で維持する。
            _denyDestination(destination)
            return
          }
          // 宛先を特定できない拒否（CONNECT 段階など）は再接続しても解消しないため止める。
          console.error('[useChatWebSocket] 宛先を特定できない購読拒否のため再接続を停止します:', frame.headers['message'])
          _rejectWaiters(new Error(`STOMP エラー: ${frame.headers['message'] ?? 'unknown'}`))
          void _stompClient?.deactivate()
        },
        onUnauthenticated: () => {
          _wsAuthState.value = 'UNAUTHENTICATED'
          _rejectWaiters(new Error('STOMP エラー: UNAUTHENTICATED'))
          void _stompClient?.deactivate()
        },
      })
      if (recovery !== null) {
        const current: Promise<void> = recovery.finally(() => {
          if (_pendingAuthRecovery === current) _pendingAuthRecovery = null
        })
        _pendingAuthRecovery = current
        return
      }
      if (destination === undefined || !_deniedDestinations.value.includes(destination)) {
        // 未知のエラー（従来どおり自動再接続に任せる）。接続待ちの送信は失敗として返す。
        _rejectWaiters(new Error(`STOMP エラー: ${frame.headers['message'] ?? 'unknown'}`))
      }
    },
  })
  _stompClient = client
  client.activate()
}

/**
 * 共有 STOMP クライアントの接続を待つ（送信用）。接続済みなら即座に resolve する。
 */
function ensureConnected(): Promise<void> {
  return new Promise<void>((resolve, reject) => {
    if (_stompClient !== null && _stompClient.connected) {
      resolve()
      return
    }
    _connectWaiters.push({ resolve, reject })
    _ensureClientActive()
  })
}

/** EventBus を呼ぶ箇所を 1 つにまとめた、チャンネルメッセージの受信処理。 */
function onChannelFrame(channelId: number, frame: IFrame): void {
  try {
    const payload = JSON.parse(frame.body) as { type: string; data: unknown }
    // MESSAGE_CREATED 受信時に最終受信IDを更新
    if (payload.type === 'MESSAGE_CREATED') {
      const msg = payload.data as { id: number }
      if (msg.id) {
        _lastMessageIdByChannel.set(channelId, msg.id)
      }
    }
    useEventBus<{ type: string; data: unknown }>('chat:ws:event').emit({
      type: payload.type,
      data: payload.data,
    })
  }
  catch (err: unknown) {
    console.error(
      `[useChatApi] チャンネル ${channelId} の受信メッセージのパースに失敗しました:`,
      err,
    )
  }
}

/**
 * 切断中に届いたメッセージを REST API でフェッチしてEventBusに流す（キャッチアップ用）。
 * REST は BE ネスト生形状を返すため、受信側ハンドラ（mapBeMessage）が変換できるよう
 * 生のまま MESSAGE_CREATED として emit する。
 */
async function _catchupMessages(channelId: number, lastMessageId: number): Promise<void> {
  if (_catchupApi === null) return
  try {
    const res = await _catchupApi<BeMessageListResponse>(
      `/api/v1/chat/channels/${channelId}/messages`,
      { query: { cursor: lastMessageId, direction: 'after', limit: 100 } },
    )
    for (const msg of res.data) {
      useEventBus<{ type: string; data: unknown }>('chat:ws:event').emit({
        type: 'MESSAGE_CREATED',
        data: msg,
      })
    }
  }
  catch {
    // キャッチアップ失敗はサイレント（次回の WS 受信で補完される）
  }
}

/**
 * チャット WebSocket（STOMP）の接続管理・購読管理を提供する composable。
 *
 * 提供する関数:
 * - sendTyping:                   タイピングインジケーター送信
 * - subscribeChannel:             メッセージトピック購読（参照カウント方式）
 * - unsubscribeChannel:           メッセージトピック購読解除
 * - subscribeChannelEvents:       イベントトピック購読
 * - unsubscribeChannelEvents:     イベントトピック購読解除
 * - wsConnectionFailed:           再接続失敗状態（5回以上失敗で true）
 * - wsUnauthenticated:            未認証で再接続を止めている状態（トークン更新でも回復しなかった）
 * - isChannelDenied:              そのチャンネルの購読が拒否されたか（閲覧権限なし）
 *
 * 認証（CMP-261104-0337・設計書 websocket_external_broker_valkey.md §2.3.1 / §2.3.2 / §2.3.3）:
 * - トークンがあれば CONNECT に Bearer を付け、無ければ付けない（Cookie 認証に任せる・空 Bearer 禁止）
 * - beforeConnect で再接続時に最新トークンへ差し替える
 * - `x-error-code: UNAUTHENTICATED` はトークン更新 1 回→再接続、失敗ならログアウト
 * - `FORBIDDEN` / `INVALID_DESTINATION` は receipt-id から拒否された宛先を特定し、その宛先だけを外して
 *   再接続する（ユーザー宛通知 `/user/queue/notifications` など他の購読は止まらない）
 */
export function useChatWebSocket() {
  // キャッチアップ用 API と認証回復手順は setup コンテキストで取得する（最初の呼び出しのものを共有）。
  const api = useApi()
  if (_catchupApi === null) _catchupApi = api
  if (_authStore === null) _authStore = useAuthStore()
  if (_authRecovery === null) _authRecovery = useStompAuthRecovery()

  /**
   * タイピングインジケーターを STOMP で送信する（デバウンス用・2秒に1回を推奨）。
   * タイピング通知は非クリティカルなため、送信失敗はサイレントに処理する。
   *
   * @param channelId 送信先チャンネル ID
   */
  function sendTyping(channelId: number): void {
    ensureConnected()
      .then(() => {
        if (_stompClient === null || !_stompClient.connected) return
        _stompClient.publish({
          destination: '/app/chat.typing',
          body: JSON.stringify({ channelId }),
        })
      })
      // eslint-disable-next-line no-restricted-syntax -- タイピング通知は非クリティカルな補助機能。WS 不通時のサイレント失敗が正しい
      .catch(() => {}) // サイレント失敗（タイピング通知は非クリティカル）
  }

  /**
   * 指定チャンネルの STOMP 購読を開始する（参照カウント方式）。
   * 同一 channelId を複数回呼んでも SUBSCRIBE は1回のみ実行される。
   *
   * @param channelId 購読対象のチャンネル ID
   */
  function subscribeChannel(channelId: number): void {
    const count = _subscriptionCounts.get(channelId) ?? 0
    _subscriptionCounts.set(channelId, count + 1)
    if (count === 0) {
      _registerRoute(`msg:${channelId}`, {
        destination: channelDestination(channelId),
        onFrame: (frame: IFrame) => onChannelFrame(channelId, frame),
      })
    }
  }

  /**
   * 指定チャンネルの STOMP 購読参照カウントをデクリメントする。
   * カウントが 0 になったら STOMP UNSUBSCRIBE を実行する。
   *
   * @param channelId 購読解除対象のチャンネル ID
   */
  function unsubscribeChannel(channelId: number): void {
    const count = _subscriptionCounts.get(channelId) ?? 0
    if (count <= 0) {
      return
    }

    const newCount = count - 1
    _subscriptionCounts.set(channelId, newCount)

    if (newCount === 0) {
      _unregisterRoute(`msg:${channelId}`)
      _subscriptionCounts.delete(channelId)
      _lastMessageIdByChannel.delete(channelId)
    }
  }

  /**
   * 指定チャンネルのイベントトピック（/topic/channels/{id}/events）を STOMP 購読する。
   * 参照カウント方式で、同一 channelId を複数回呼んでも SUBSCRIBE は 1 回のみ実行される。
   *
   * 受信したイベントは {@code useEventBus<{ channelId, event }>('chat:channel:event')} で配信される。
   *
   * @param channelId 購読対象のチャンネル ID
   */
  function subscribeChannelEvents(channelId: number): void {
    const count = _eventSubscriptionCounts.get(channelId) ?? 0
    _eventSubscriptionCounts.set(channelId, count + 1)
    if (count === 0) {
      _registerRoute(`evt:${channelId}`, {
        destination: channelEventsDestination(channelId),
        onFrame: (frame: IFrame) => {
          try {
            const event = JSON.parse(frame.body) as ChatChannelEvent
            useEventBus<{ channelId: number; event: ChatChannelEvent }>(
              'chat:channel:event',
            ).emit({ channelId, event })
          }
          catch (err: unknown) {
            console.error(
              `[useChatApi] チャンネル ${channelId} のイベントパースに失敗しました:`,
              err,
            )
          }
        },
      })
    }
  }

  /**
   * 指定チャンネルのイベント購読参照カウントをデクリメントする。
   * カウントが 0 になったら STOMP UNSUBSCRIBE を実行する。
   *
   * @param channelId 購読解除対象のチャンネル ID
   */
  function unsubscribeChannelEvents(channelId: number): void {
    const count = _eventSubscriptionCounts.get(channelId) ?? 0
    if (count <= 0) {
      return
    }

    const newCount = count - 1
    _eventSubscriptionCounts.set(channelId, newCount)

    if (newCount === 0) {
      _unregisterRoute(`evt:${channelId}`)
      _eventSubscriptionCounts.delete(channelId)
    }
  }

  /**
   * 任意の STOMP トピックを購読する（在席・ユーザー宛通知等チャット以外の用途向け）。
   * 再接続時は自動で張り直す。
   * @returns 購読解除関数（onBeforeUnmount 等で呼ぶこと）
   */
  function subscribeRaw(topic: string, callback: (body: string) => void): () => void {
    const key = `raw:${++_rawSeq}`
    _registerRoute(key, {
      destination: topic,
      onFrame: (frame: IFrame) => callback(frame.body),
    })
    return () => {
      _unregisterRoute(key)
    }
  }

  /**
   * 任意の宛先に STOMP メッセージを送信する（在席等チャット以外の用途向け）。
   */
  function publishRaw(destination: string, body = '{}'): void {
    ensureConnected()
      .then(() => {
        if (_stompClient === null || !_stompClient.connected) return
        _stompClient.publish({ destination, body })
      })
      // eslint-disable-next-line no-restricted-syntax -- 在席等の任意 STOMP 送信。WS 接続不可時のサイレント失敗が正しい（本文は REST 経由・補助機能）
      .catch(() => {}) // WebSocket 接続失敗時のサイレント失敗（在席等の補助機能・非クリティカル）
  }

  /** そのチャンネル（メッセージ／イベント）の購読が拒否されたか（閲覧権限なし）。 */
  function isChannelDenied(channelId: number): boolean {
    return _deniedDestinations.value.includes(channelDestination(channelId))
      || _deniedDestinations.value.includes(channelEventsDestination(channelId))
  }

  return {
    sendTyping,
    subscribeChannel,
    unsubscribeChannel,
    subscribeChannelEvents,
    unsubscribeChannelEvents,
    wsConnectionFailed: _wsConnectionFailed,
    wsUnauthenticated: computed(() => _wsAuthState.value === 'UNAUTHENTICATED'),
    deniedDestinations: readonly(_deniedDestinations),
    isChannelDenied,
    subscribeRaw,
    publishRaw,
  }
}

// ============================================================
// Visibility API 連携（モジュールレベル・SSRガード付き）
// ============================================================

/** Visibility API でタブ状態に応じて reconnectDelay を調整する。 */
if (import.meta.client) {
  document.addEventListener('visibilitychange', () => {
    if (_stompClient === null) return
    if (document.hidden) {
      // バックグラウンドタブ: 再接続間隔を延長
      _stompClient.reconnectDelay = 60_000
    }
    else {
      // フォアグラウンド復帰: 即再接続
      _stompClient.reconnectDelay = 1000
      // 未認証で止めた接続は、ここでは再開しない（再接続しても解消しないため）。
      if (!_stompClient.connected && _wsAuthState.value !== 'UNAUTHENTICATED') {
        _stompClient.activate()
      }
    }
  })
}
