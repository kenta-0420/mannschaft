import type { Client, IFrame, StompSubscription } from '@stomp/stompjs'
import { Client as StompClient } from '@stomp/stompjs'
import { useEventBus } from '@vueuse/core'
import { ref } from 'vue'
import { useWsUrl } from '~/composables/useWsUrl'
import type { ChatChannelEvent } from '~/types/chat'
import type { BeMessageListResponse } from './chatMessageMapper'

// ============================================================
// モジュールレベルのシングルトン状態（composable再呼び出しを跨いで維持）
// ============================================================

const _subscriptionCounts = new Map<number, number>()
const _stompSubscriptions = new Map<number, StompSubscription>()
const _eventSubscriptionCounts = new Map<number, number>()
const _eventStompSubscriptions = new Map<number, StompSubscription>()
let _stompClient: Client | null = null
/** 再接続後に再サブスクリプションするチャンネルのセット */
const _subscribedChannels = new Set<number>()
/** 各チャンネルで最後に受信した MESSAGE_CREATED の messageId（キャッチアップ用） */
const _lastMessageIdByChannel = new Map<number, number>()
/** 指数バックオフの試行回数 */
let _reconnectAttempts = 0
/** 5回以上連続で再接続失敗した場合に true になるフラグ */
const _wsConnectionFailed = ref(false)
/** 初回接続かどうか（再接続後の再サブスクリプション判定用） */
let _isFirstConnect = true

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
 *
 * F04.2.1 Phase10:
 * - CONNECT フレームに Authorization ヘッダー（Bearer トークン）を付与する
 * - beforeConnect で再接続時に最新トークンへ差し替える（リフレッシュ対応）
 */
export function useChatWebSocket() {
  const api = useApi()
  // dev 環境（FE :3000 / BE :8080 が別ポート）では apiBase が絶対 URL になるため、
  // ws(s) スキームに変換してバックエンドの /ws/websocket エンドポイントへ正しく接続する
  // （共通ヘルパー useWsUrl 経由。BE は SockJS 登録のみのため bare /ws は 400 になる）。
  // 本番（同一オリジン構成）では apiBase が空のため '/ws/websocket' のまま（挙動不変）。
  const wsUrl = useWsUrl()

  /**
   * STOMP クライアントが未接続なら接続する。
   * 接続済みの場合は即座に resolve する。
   */
  function ensureConnected(): Promise<void> {
    return new Promise<void>((resolve, reject) => {
      if (_stompClient !== null && _stompClient.connected) {
        resolve()
        return
      }

      const auth = useAuthStore()
      const client = new StompClient({
        webSocketFactory: () => new WebSocket(wsUrl),
        connectHeaders: { Authorization: `Bearer ${auth.accessToken ?? ''}` },
        beforeConnect: () => {
          // 再接続時に最新トークンを差し替える（リフレッシュ後の再接続でも有効なトークンを使う）
          if (_stompClient !== null) {
            _stompClient.connectHeaders = {
              Authorization: `Bearer ${useAuthStore().accessToken ?? ''}`,
            }
          }
        },
        reconnectDelay: 1000,
        onConnect: () => {
          if (!_isFirstConnect) {
            // 再接続時: 全チャンネルを再サブスクリプション
            for (const channelId of _subscribedChannels) {
              _resubscribeChannel(channelId)
            }
            // 切断中に届いたメッセージをキャッチアップ
            for (const [channelId, lastId] of _lastMessageIdByChannel) {
              _catchupMessages(channelId, lastId)
            }
          }
          _isFirstConnect = false
          _reconnectAttempts = 0
          _wsConnectionFailed.value = false
          resolve()
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
          reject(new Error(`STOMP エラー: ${frame.headers['message'] ?? 'unknown'}`))
        },
      })
      _stompClient = client
      client.activate()
    })
  }

  /** 再接続後に指定チャンネルを再サブスクリプションする（内部用）。 */
  function _resubscribeChannel(channelId: number): void {
    if (_stompClient === null || !_stompClient.connected) return
    // 古いサブスクリプションを破棄
    _stompSubscriptions.get(channelId)?.unsubscribe()
    _stompSubscriptions.delete(channelId)
    // 新しいサブスクリプションを登録
    const subscription = _stompClient.subscribe(
      `/topic/channels/${channelId}`,
      (frame: IFrame) => {
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
        } catch (err: unknown) {
          console.error(
            `[useChatApi] チャンネル ${channelId} の受信メッセージのパースに失敗しました:`,
            err,
          )
        }
      },
    )
    _stompSubscriptions.set(channelId, subscription)
  }

  /**
   * 切断中に届いたメッセージを REST API でフェッチしてEventBusに流す（キャッチアップ用）。
   * REST は BE ネスト生形状を返すため、受信側ハンドラ（mapBeMessage）が変換できるよう
   * 生のまま MESSAGE_CREATED として emit する。
   */
  async function _catchupMessages(channelId: number, lastMessageId: number): Promise<void> {
    try {
      const res = await api<BeMessageListResponse>(
        `/api/v1/chat/channels/${channelId}/messages`,
        { query: { cursor: lastMessageId, direction: 'after', limit: 100 } },
      )
      for (const msg of res.data) {
        useEventBus<{ type: string; data: unknown }>('chat:ws:event').emit({
          type: 'MESSAGE_CREATED',
          data: msg,
        })
      }
    } catch {
      // キャッチアップ失敗はサイレント（次回の WS 受信で補完される）
    }
  }

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
      _subscribedChannels.add(channelId)
      // 初回のみ STOMP SUBSCRIBE を実行
      ensureConnected()
        .then(() => {
          if (_stompClient === null) return

          const subscription = _stompClient.subscribe(
            `/topic/channels/${channelId}`,
            (frame: IFrame) => {
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
              } catch (err: unknown) {
                console.error(
                  `[useChatApi] チャンネル ${channelId} の受信メッセージのパースに失敗しました:`,
                  err,
                )
              }
            },
          )
          _stompSubscriptions.set(channelId, subscription)
        })
        .catch((err: unknown) => {
          console.error(`[useChatApi] チャンネル ${channelId} の購読接続に失敗しました:`, err)
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
      _stompSubscriptions.get(channelId)?.unsubscribe()
      _stompSubscriptions.delete(channelId)
      _subscriptionCounts.delete(channelId)
      _subscribedChannels.delete(channelId)
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
      ensureConnected()
        .then(() => {
          if (_stompClient === null) return

          const subscription = _stompClient.subscribe(
            `/topic/channels/${channelId}/events`,
            (frame: IFrame) => {
              try {
                const event = JSON.parse(frame.body) as ChatChannelEvent
                useEventBus<{ channelId: number; event: ChatChannelEvent }>(
                  'chat:channel:event',
                ).emit({ channelId, event })
              } catch (err: unknown) {
                console.error(
                  `[useChatApi] チャンネル ${channelId} のイベントパースに失敗しました:`,
                  err,
                )
              }
            },
          )
          _eventStompSubscriptions.set(channelId, subscription)
        })
        .catch((err: unknown) => {
          console.error(
            `[useChatApi] チャンネル ${channelId} のイベント購読接続に失敗しました:`,
            err,
          )
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
      _eventStompSubscriptions.get(channelId)?.unsubscribe()
      _eventStompSubscriptions.delete(channelId)
      _eventSubscriptionCounts.delete(channelId)
    }
  }

  /**
   * 任意の STOMP トピックを購読する（在席等チャット以外の用途向け）。
   * @returns 購読解除関数（onBeforeUnmount 等で呼ぶこと）
   */
  function subscribeRaw(topic: string, callback: (body: string) => void): () => void {
    let subscription: StompSubscription | null = null
    ensureConnected()
      .then(() => {
        if (_stompClient === null) return
        subscription = _stompClient.subscribe(topic, (frame: IFrame) => {
          callback(frame.body)
        })
      })
      .catch(() => {}) // WebSocket 接続失敗時のサイレント失敗（在席等の補助機能・非クリティカル）
    return () => {
      subscription?.unsubscribe()
      subscription = null
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
      .catch(() => {}) // WebSocket 接続失敗時のサイレント失敗（在席等の補助機能・非クリティカル）
  }

  return {
    sendTyping,
    subscribeChannel,
    unsubscribeChannel,
    subscribeChannelEvents,
    unsubscribeChannelEvents,
    wsConnectionFailed: _wsConnectionFailed,
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
    } else {
      // フォアグラウンド復帰: 即再接続
      _stompClient.reconnectDelay = 1000
      if (!_stompClient.connected) {
        _stompClient.activate()
      }
    }
  })
}
