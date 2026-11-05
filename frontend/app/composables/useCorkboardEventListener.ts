/**
 * F09.8 Phase F: コルクボード WebSocket リアルタイム同期 composable。
 *
 * 共有ボード（TEAM / ORGANIZATION）の詳細ページで STOMP `/topic/corkboard/{boardId}` を購読し、
 * バックエンドから配信される {@link CorkboardEventPayload} を受信して呼び出し側に通知する。
 * 個人ボード（PERSONAL）は配信対象外なので呼び出さない（ボード詳細ページで判定）。
 *
 * 設計方針:
 *  - F04.2 チャットの `useChatWebSocket` と同じ STOMP 接続パターンを使う
 *    （共通補助 `stomp/stompAuth` の接続ヘッダ: トークンがあれば Bearer、無ければ付けず Cookie 認証に任せる。
 *     `beforeConnect` でのトークン差し替え、`reconnectDelay: 5000` による自動再接続）。
 *  - 同一 boardId に対する複数購読を防ぐため、参照カウント方式で SUBSCRIBE / UNSUBSCRIBE
 *    を 1 回ずつに集約する。これにより同一ページが SPA 内で再 mount された場合でも安全。
 *  - 再接続したら購読中のボードを張り直す（切断で購読は失われるため）。
 *  - 拒否は ERROR フレームの `x-error-code` で判定する（設計書 websocket_external_broker_valkey.md §2.3.2）。
 *    UNAUTHENTICATED はトークン更新 1 回→再接続（失敗でログアウト）、FORBIDDEN / INVALID_DESTINATION は
 *    再接続を止める。未知のエラーは console.error で報告し、従来どおり自動再接続に任せる。
 *  - イベント受信時の更新戦略は MVP として「親側でフルリロード」を採用（設計書 §5 切断時復帰）。
 *    eventType ごとの局所更新は将来 Phase で精緻化する。
 *
 * 制限事項（v1.0）:
 *  - 個人ボードでの「同一ユーザーの複数タブ間同期」は対象外。タブごとに `load()` で再取得する運用。
 *  - 切断中の接続状態 UI（「再接続中…」インジケータ等）は Phase F では未実装。
 *    拒否・未認証の確定状態は `connectionState` で公開し、`useCorkboardWebSocketSync` の `realtimeState`
 *    経由でボード詳細画面に表示する。停止後も、新しい購読（別ボードへの移動・再ログイン後の再訪）で再開する。
 */
import type { Client, IFrame, IMessage, StompSubscription } from '@stomp/stompjs'
import { Client as StompClient } from '@stomp/stompjs'
import {
  buildStompConnectHeaders,
  handleStompErrorFrame,
  useStompAuthRecovery,
  type StompAuthRecovery,
} from '~/composables/stomp/stompAuth'
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

/**
 * 共有接続の状態。
 * - OK:              通常（接続中・再接続中を含む）
 * - DENIED:          購読拒否（FORBIDDEN / INVALID_DESTINATION）。再接続を止めた
 * - UNAUTHENTICATED: 未認証でトークン更新でも回復しなかった。再接続を止めた
 */
export type CorkboardConnectionState = 'OK' | 'DENIED' | 'UNAUTHENTICATED'
const _connectionState = ref<CorkboardConnectionState>('OK')
/** UNAUTHENTICATED の回復手順（最初の composable 呼び出し＝setup コンテキストで取得）。 */
let _authRecovery: StompAuthRecovery | null = null
let _authStore: ReturnType<typeof useAuthStore> | null = null
/** 進行中の UNAUTHENTICATED 回復。beforeConnect で完了を待ってから再接続する。 */
let _pendingAuthRecovery: Promise<void> | null = null

export interface UseCorkboardEventListenerOptions {
  /** 購読対象のボード ID */
  boardId: number
  /** 受信イベントのハンドラ。フルリロード起動など、親側で再描画する */
  onEvent: (event: CorkboardEventPayload) => void
}

/** 受信メッセージを当該 boardId の全ハンドラへ配る。パース失敗は握りつぶさず console.error で報告する。 */
function dispatchBoardMessage(boardId: number, msg: IMessage): void {
  try {
    const event = JSON.parse(msg.body) as CorkboardEventPayload
    const targets = _eventHandlers.get(boardId)
    if (targets) {
      for (const fn of targets) {
        fn(event)
      }
    }
  }
  catch (err: unknown) {
    console.error(
      `[useCorkboardEventListener] ボード ${boardId} の受信メッセージのパースに失敗しました:`,
      err,
    )
  }
}

/** 現在の接続上で指定ボードを購読する（接続済み・購読中のときのみ）。 */
function subscribeOnCurrentConnection(boardId: number): void {
  if (_stompClient === null || !_stompClient.connected) return
  if ((_subscriptionCounts.get(boardId) ?? 0) <= 0) return
  _stompSubscriptions.get(boardId)?.unsubscribe()
  const subscription = _stompClient.subscribe(
    `/topic/corkboard/${boardId}`,
    (msg: IMessage) => dispatchBoardMessage(boardId, msg),
  )
  _stompSubscriptions.set(boardId, subscription)
}

/**
 * STOMP クライアントを必要に応じて生成・活性化する。
 * 接続すると onConnect が購読中の全ボードを（再）購読する。
 */
function ensureClientActive(): void {
  if (_stompClient !== null) {
    if (_stompClient.connected) {
      return
    }
    if (_stompClient.active === false) {
      // DENIED / UNAUTHENTICATED で止めた接続も、新しい購読（別ボードへの移動・再ログイン後の再訪）を機に
      // 再開する。拒否・未認証が続くなら、サーバが再び同じ理由で拒否して停止状態に戻る。
      _connectionState.value = 'OK'
      _stompClient.activate()
    }
    return
  }

  // BE の /ws は SockJS 登録のみのため、生 WebSocket は /ws/websocket でしか接続できない
  // （bare /ws は 400。共通ヘルパー useWsUrl 経由で apiBase 込みの URL を解決する）。
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
    reconnectDelay: 5000,
    onConnect: () => {
      // 初回・再接続とも、購読中の全ボードを張り直す（切断で購読は失われるため）。
      for (const boardId of _subscriptionCounts.keys()) {
        subscribeOnCurrentConnection(boardId)
      }
    },
    onWebSocketClose: () => {
      _stompSubscriptions.clear()
    },
    onStompError: (frame: IFrame) => {
      if (_authRecovery === null) {
        console.error('[useCorkboardEventListener] STOMP エラー:', frame.headers['message'] ?? 'unknown')
        return
      }
      const recovery = handleStompErrorFrame(frame, _authRecovery, {
        onDenied: () => {
          _connectionState.value = 'DENIED'
          void _stompClient?.deactivate()
        },
        onUnauthenticated: () => {
          _connectionState.value = 'UNAUTHENTICATED'
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
      // 未知のエラーや拒否は握りつぶさず報告する（未知のエラーは従来どおり自動再接続）。
      console.error(
        '[useCorkboardEventListener] STOMP エラー:',
        frame.headers['message'] ?? 'unknown',
      )
    },
  })
  _stompClient = client
  client.activate()
}

/**
 * 指定ボードの STOMP 購読を開始する（参照カウント方式）。
 * 同一 boardId を複数回呼んでも SUBSCRIBE は 1 回のみ実行される。
 *
 * 受信したペイロードは、当該 boardId に登録された全ハンドラへ順に配信される。
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
    // 初回のみ STOMP SUBSCRIBE を実行（未接続なら接続後に onConnect が購読する）
    if (_stompClient !== null && _stompClient.connected) {
      subscribeOnCurrentConnection(boardId)
    }
    else {
      ensureClientActive()
    }
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
  connectionState: Readonly<Ref<CorkboardConnectionState>>
} {
  const { boardId, onEvent } = options
  let connected = false
  // 認証回復手順は setup コンテキストで取得する（最初の呼び出しのものを共有）。
  if (_authStore === null) _authStore = useAuthStore()
  if (_authRecovery === null) _authRecovery = useStompAuthRecovery()

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

  return { connect, disconnect, connectionState: readonly(_connectionState) }
}
