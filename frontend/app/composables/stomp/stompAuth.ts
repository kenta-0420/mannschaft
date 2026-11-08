/**
 * STOMP（`/ws`）接続の認証ヘッダと拒否理由（`x-error-code`）の共通補助。
 *
 * 設計書: docs/architecture/websocket_external_broker_valkey.md §2.3.1 / §2.3.2 / §2.3.3（FE 側の扱い）。
 *
 * ## 身元の決まり方（BE 契約）
 * CONNECT の身元は「有効な Bearer ＞ 認証済みハンドシェイク Cookie ＞ 匿名」で決まる。
 * 値のある無効 Bearer は Cookie へフォールバックせず `UNAUTHENTICATED` で拒否される。
 * FE はアクセストークンをメモリにのみ持つため、リロード直後は accessToken が null になる。
 * このとき空の `Authorization: Bearer ` を送らず、**ヘッダ自体を付けない**（Cookie 認証に任せる）。
 *
 * ## 拒否理由
 * 拒否は STOMP ERROR フレームのネイティブヘッダ `x-error-code` で届き、ERROR 後はサーバが接続を閉じる。
 * - `UNAUTHENTICATED`: トークン更新を 1 回試みてから再接続する（{@link createStompAuthRecovery}）
 * - `FORBIDDEN` / `INVALID_DESTINATION`: 再接続しても解消しない → 再接続を止める
 * - それ以外（ヘッダなし）: 未知のエラー → 従来どおり自動再接続に任せる
 */
import type { IFrame, StompHeaders } from '@stomp/stompjs'
import { handleAuthFailureLogout, performTokenRefresh } from '~/composables/useApi'

/** BE `StompErrorCode.HEADER` と一致させる ERROR フレームのネイティブヘッダ名。 */
export const STOMP_ERROR_CODE_HEADER = 'x-error-code'

/** ERROR フレームの拒否理由（BE の 3 コード＋未知）。 */
export type StompRejectCode = 'UNAUTHENTICATED' | 'FORBIDDEN' | 'INVALID_DESTINATION' | 'UNKNOWN'

/**
 * CONNECT ヘッダを組み立てる（4 composable 共通・空 Bearer 禁止）。
 * トークンが無い（null・空・空白のみ）ときは Authorization を付けず、ハンドシェイク Cookie の認証に任せる。
 */
export function buildStompConnectHeaders(accessToken: string | null | undefined): StompHeaders {
  const token = accessToken?.trim()
  if (!token) return {}
  return { Authorization: `Bearer ${token}` }
}

/** ERROR フレームから拒否理由コードを読む。知らない値・ヘッダなしは UNKNOWN。 */
export function readStompErrorCode(frame: Pick<IFrame, 'headers'>): StompRejectCode {
  const raw = frame.headers[STOMP_ERROR_CODE_HEADER]
  if (raw === 'UNAUTHENTICATED' || raw === 'FORBIDDEN' || raw === 'INVALID_DESTINATION') return raw
  return 'UNKNOWN'
}

/** 再接続しても解消しない拒否か（FORBIDDEN / INVALID_DESTINATION）。 */
export function isPermanentRejection(code: StompRejectCode): boolean {
  return code === 'FORBIDDEN' || code === 'INVALID_DESTINATION'
}

/**
 * `UNAUTHENTICATED` を受けたときの回復結果。
 * - RETRY:           トークン更新に成功。新トークンで再接続してよい
 * - TRANSIENT:       更新が一時的に失敗（通信断・5xx）。従来どおり自動再接続に任せる
 * - ANONYMOUS:       未ログイン（匿名の閲覧者）。更新もログアウトもしない。再接続は止める
 * - LOGGED_OUT:      refresh_token が無効。既存のログアウト経路でログアウト済み。再接続は止める
 * - EXHAUSTED:       直近に更新したのに再び未認証。更新を繰り返さず再接続を止める
 * - SESSION_CHANGED: 更新中に本人セッションが入れ替わった。再接続は止める
 */
export type StompAuthRecoveryOutcome =
  | 'RETRY'
  | 'TRANSIENT'
  | 'ANONYMOUS'
  | 'LOGGED_OUT'
  | 'EXHAUSTED'
  | 'SESSION_CHANGED'

/** 回復後も再接続を続けてよいか。 */
export function shouldReconnectAfterRecovery(outcome: StompAuthRecoveryOutcome): boolean {
  return outcome === 'RETRY' || outcome === 'TRANSIENT'
}

/**
 * この時間内に再び `UNAUTHENTICATED` が来たら、更新を繰り返さず諦める（更新は 1 回まで）。
 * 正常なら更新直後の再接続は成功するため、窓内の再拒否は更新では直らない状態を意味する。
 */
export const STOMP_AUTH_RETRY_WINDOW_MS = 30_000

export interface StompAuthRecoveryDeps {
  config: Parameters<typeof performTokenRefresh>[0]
  authStore: Parameters<typeof performTokenRefresh>[1]
  /** テスト用の時計。 */
  now?: () => number
}

/**
 * `UNAUTHENTICATED` の回復手順（トークン更新 1 回 → 失敗ならログアウト）を作る。
 *
 * - 更新は既存の {@link performTokenRefresh} を使う。同一ストア・同一セッションの更新は 1 本の
 *   Promise に束ねられるため、複数の STOMP 接続から同時に `UNAUTHENTICATED` が来ても更新は 1 回で済む。
 * - ログアウトは既存の {@link handleAuthFailureLogout}（こちらも 1 回に束ねる）を使う。
 * - 未ログイン（`isAuthenticated=false`）の匿名接続では更新もログアウトもしない（公開試合の匿名観戦など）。
 *
 * config / authStore は同期の setup コンテキストで取得したものを渡すこと（useApi と同じ作法）。
 */
export function createStompAuthRecovery(deps: StompAuthRecoveryDeps) {
  const now = deps.now ?? Date.now
  let lastRefreshAt: number | null = null

  async function recover(): Promise<StompAuthRecoveryOutcome> {
    if (!deps.authStore.isAuthenticated) return 'ANONYMOUS'
    const at = now()
    if (lastRefreshAt !== null && at - lastRefreshAt < STOMP_AUTH_RETRY_WINDOW_MS) return 'EXHAUSTED'
    lastRefreshAt = at
    const result = await performTokenRefresh(deps.config, deps.authStore)
    if (result === 'refreshed') return 'RETRY'
    if (result === 'transient') {
      // 一時的失敗は「1 回」に数えない（回線が戻れば次の拒否で再び更新を試みる）。
      lastRefreshAt = null
      return 'TRANSIENT'
    }
    if (result === 'session_changed') return 'SESSION_CHANGED'
    await handleAuthFailureLogout(deps.authStore)
    return 'LOGGED_OUT'
  }

  return { recover }
}

export type StompAuthRecovery = ReturnType<typeof createStompAuthRecovery>

/** {@link handleStompErrorFrame} の呼び出し側処理。 */
export interface StompRejectionHandlers {
  /** FORBIDDEN / INVALID_DESTINATION（閲覧権限なし）。呼び出し側は再接続を止める。 */
  onDenied: (code: StompRejectCode) => void
  /** 回復できない未認証（匿名・ログアウト済み・更新しても未認証）。呼び出し側は再接続を止める。 */
  onUnauthenticated: (outcome: StompAuthRecoveryOutcome) => void
}

/**
 * 専用接続（1 接続 1 宛先）の ERROR フレームを捌く。
 *
 * 戻り値は `UNAUTHENTICATED` の回復処理の Promise（それ以外は null）。呼び出し側は stompjs の
 * `beforeConnect` でこれを待ってから最新トークンでヘッダを組み直すことで、更新完了後に再接続する。
 * 未知のコードは何もしない（サーバが接続を閉じ、stompjs が従来どおり自動再接続する）。
 */
export function handleStompErrorFrame(
  frame: Pick<IFrame, 'headers'>,
  recovery: StompAuthRecovery,
  handlers: StompRejectionHandlers,
): Promise<void> | null {
  const code = readStompErrorCode(frame)
  if (isPermanentRejection(code)) {
    handlers.onDenied(code)
    return null
  }
  if (code !== 'UNAUTHENTICATED') return null
  return recovery.recover().then(
    (outcome) => {
      if (!shouldReconnectAfterRecovery(outcome)) handlers.onUnauthenticated(outcome)
    },
    (error: unknown) => {
      // ログアウト処理自体の失敗。症状は隠さず報告し、未認証として再接続を止める。
      console.error('[stompAuth] UNAUTHENTICATED の回復に失敗しました:', error)
      handlers.onUnauthenticated('LOGGED_OUT')
    },
  )
}

/** setup コンテキストで config / authStore を取得して回復手順を作る。 */
export function useStompAuthRecovery(): StompAuthRecovery {
  return createStompAuthRecovery({ config: useRuntimeConfig(), authStore: useAuthStore() })
}
