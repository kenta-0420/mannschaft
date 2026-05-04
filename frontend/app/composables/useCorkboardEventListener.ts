/**
 * F09.8 Phase F: コルクボード WebSocket リアルタイム同期 composable。
 *
 * 共有ボード（TEAM / ORGANIZATION）の詳細ページで STOMP `/topic/corkboard/{boardId}` を購読し、
 * バックエンドから配信される {@link CorkboardEventPayload} を受信して呼び出し側に通知する。
 * 個人ボード（PERSONAL）は配信対象外なので呼び出さない（ボード詳細ページで判定）。
 *
 * 設計方針:
 *  - F04.2 チャットの `useChatApi` で実装済みの STOMP 接続パターンをそのまま流用する
 *    （`Authorization: Bearer` ヘッダ、`beforeConnect` でのトークン差し替え、
 *     `reconnectDelay: 5000` による自動再接続）。
 *  - 同一 boardId に対する複数購読を防ぐため、参照カウント方式で SUBSCRIBE / UNSUBSCRIBE
 *    を 1 回ずつに集約する。これにより同一ページが SPA 内で再 mount された場合でも安全。
 *  - イベント受信時の更新戦略は MVP として「親側でフルリロード」を採用（設計書 §5 切断時復帰）。
 *    eventType ごとの局所更新は将来 Phase で精緻化する。
 *
 * 制限事項（v1.0）:
 *  - 個人ボードでの「同一ユーザーの複数タブ間同期」は対象外。タブごとに `load()` で再取得する運用。
 *  - 切断中の接続状態 UI（「再接続中…」インジケータ等）は Phase F では未実装。
 *    再接続自体は STOMP クライアントが自動で行うため、ユーザーには通常は不可視。
 */
import type { Client, IMessage, StompSubscription } from '@stomp/stompjs'
import { Client as StompClient } from '@stomp/stompjs'
import type { CorkboardEventPayload } from '~/types/corkboard'

// ============================================================
// モジュールレベルのシングルトン状態
// （composable 再呼び出しを跨いで維持する）
// ============================================================

/** boardId → 参照カウント */
const _subscriptionCounts = new Map<number, number>()
/** boardId → STOMP Subscription（unsubscribe するため保持） */
const _stompSubscriptions = new Map<number, StompSubscription>()
/** boardId → 受信ハンドラ集合（1 boardId に対して複数 listener が連結する場合の配信用） */
const _eventHandlers = new Map<number, Set<(event: CorkboardEventPayload) => void>>()
/** プロセス全体で 1 つの STOMP クライアントを共有する */
let _stompClient: Client | null = null

export interface UseCorkboardEventListenerOptions {
  /** 購読対象のボード ID */
  boardId: number
  /** 受信イベントのハンドラ。フルリロード起動など、親側で再描画する */
  onEvent: (event: CorkboardEventPayload) => void
}

/**
 * STOMP クライアントを必要に応じて生成・接続する。
 * 既に接続済みなら即 resolve する。
 *
 * F04.2 `useChatApi.ensureConnected` と同一パターン:
 *  - CONNECT フレームに `Authorization: Bearer <accessToken>` を付与
 *  - `beforeConnect` で再接続時にも最新トークンへ差し替え（リフレッシュ対応）
 *  - `reconnectDelay: 5000` で WebSocket 切断時に自動再接続
 */
function ensureConnected(): Promise<void> {
  return new Promise<void>((resolve, reject) => {
    if (_stompClient !== null && _stompClient.connected) {
      resolve()
      return
    }

    const auth = useAuthStore()
    const client = new StompClient({
      webSocketFactory: () => new WebSocket('/ws'),
      connectHeaders: { Authorization: `Bearer ${auth.accessToken ?? ''}` },
      beforeConnect: () => {
        // 再接続時に最新トークンを差し替える（リフレッシュ後の再接続でも有効なトークンを使う）
        if (_stompClient !== null) {
          _stompClient.connectHeaders = {
            Authorization: `Bearer ${useAuthStore().accessToken ?? ''}`,
          }
        }
      },
      reconnectDelay: 5000,
      onConnect: () => {
        resolve()
      },
      onStompError: (frame) => {
        reject(new Error(`STOMP エラー: ${frame.headers['message'] ?? 'unknown'}`))
      },
    })
    _stompClient = client
    client.activate()
  })
}

/**
 * 指定ボードの STOMP 購読を開始する（参照カウント方式）。
 * 同一 boardId を複数回呼んでも SUBSCRIBE は 1 回のみ実行される。
 *
 * 受信したペイロードは、当該 boardId に登録された全ハンドラへ順に配信される。
 * パース失敗時はコンソール警告のみで握りつぶさず error として扱う（症状を隠さない）。
 */
function subscribeBoard(
  boardId: number,
  handler: (event: CorkboardEventPayload) => void,
): void {
  // ハンドラを集合に追加
  let handlers = _eventHandlers.get(boardId)
  if (!handlers) {
    handlers = new Set()
    _eventHandlers.set(boardId, handlers)
  }
  handlers.add(handler)

  const count = _subscriptionCounts.get(boardId) ?? 0
  _subscriptionCounts.set(boardId, count + 1)

  if (count === 0) {
    // 初回のみ STOMP SUBSCRIBE を実行
    ensureConnected()
      .then(() => {
        if (_stompClient === null) return

        const subscription = _stompClient.subscribe(
          `/topic/corkboard/${boardId}`,
          (msg: IMessage) => {
            try {
              const event = JSON.parse(msg.body) as CorkboardEventPayload
              const targets = _eventHandlers.get(boardId)
              if (targets) {
                for (const fn of targets) {
                  fn(event)
                }
              }
            } catch (err: unknown) {
              console.error(
                `[useCorkboardEventListener] ボード ${boardId} の受信メッセージのパースに失敗しました:`,
                err,
              )
            }
          },
        )
        _stompSubscriptions.set(boardId, subscription)
      })
      .catch((err: unknown) => {
        console.error(
          `[useCorkboardEventListener] ボード ${boardId} の購読接続に失敗しました:`,
          err,
        )
      })
  }
}

/**
 * 指定ボードの購読参照カウントをデクリメントする。
 * カウントが 0 になったら STOMP UNSUBSCRIBE を実行する。
 */
function unsubscribeBoard(
  boardId: number,
  handler: (event: CorkboardEventPayload) => void,
): void {
  const handlers = _eventHandlers.get(boardId)
  if (handlers) {
    handlers.delete(handler)
    if (handlers.size === 0) {
      _eventHandlers.delete(boardId)
    }
  }

  const count = _subscriptionCounts.get(boardId) ?? 0
  if (count <= 0) {
    return
  }

  const newCount = count - 1
  _subscriptionCounts.set(boardId, newCount)

  if (newCount === 0) {
    _stompSubscriptions.get(boardId)?.unsubscribe()
    _stompSubscriptions.delete(boardId)
    _subscriptionCounts.delete(boardId)
  }
}

/**
 * コルクボード WebSocket リアルタイム同期の購読制御 composable。
 *
 * @example
 * ```ts
 * const listener = useCorkboardEventListener({
 *   boardId: 42,
 *   onEvent: (event) => {
 *     // フルリロード戦略
 *     load()
 *   },
 * })
 * listener.connect()
 * onUnmounted(() => listener.disconnect())
 * ```
 */
export function useCorkboardEventListener(options: UseCorkboardEventListenerOptions): {
  connect: () => void
  disconnect: () => void
} {
  const { boardId, onEvent } = options
  let connected = false

  const connect = (): void => {
    if (connected) return
    connected = true
    subscribeBoard(boardId, onEvent)
  }

  const disconnect = (): void => {
    if (!connected) return
    connected = false
    unsubscribeBoard(boardId, onEvent)
  }

  return { connect, disconnect }
}
