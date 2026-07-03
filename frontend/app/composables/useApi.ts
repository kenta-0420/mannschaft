import { ofetch } from 'ofetch'
import { resolveApiBaseUrl } from '~/composables/useApiBaseUrl'

/**
 * トークンリフレッシュの結果を表す 3 状態。
 *
 * boolean（成功/失敗）だと「本物の認証失敗（refresh_token 無効）」と
 * 「一時的な失敗（timeout / ネットワーク / 5xx）」が区別できず、回線が遅いだけの
 * ユーザーを誤ってログアウトさせてしまう。安全のため 3 状態に分離する。
 *
 * - 'refreshed'   : 更新成功（新トークンを setTokens 済み）
 * - 'auth_failed' : refresh エンドポイントが 401/403 ＝ refresh_token が無効な本物の認証失敗
 * - 'transient'   : timeout / abort / ネットワークエラー / 5xx ＝ 一時的（回線が遅い/詰まっただけ）
 */
export type TokenRefreshResult = 'refreshed' | 'auth_failed' | 'transient'

// refresh API のハング保険。15 秒で abort する。
// これが無いと refresh がハングした際に await が永久 pending となり、
// 先回り更新を await している async プラグイン（auth.client）が app mount をブロックし、
// layouts/default.vue の LoadingBounce フォールバックが固着して白画面化する。
const REFRESH_TIMEOUT_MS = 15_000

let refreshPromise: Promise<TokenRefreshResult> | null = null

/**
 * access_token の先回り／事後リフレッシュを行うモジュールスコープ関数。
 *
 * 定期的な先回りリフレッシュ（armProactiveRefresh スケジューラ）と interceptor の 401 リカバリの
 * 両方から呼ばれ、モジュール level の refreshPromise で二重リフレッシュを防止する
 * （同時に複数の呼び出しが来ても 1 本の Promise を共有する）。
 *
 * config / authStore は引数で受け取る。リクエスト時コンテキスト（イベントハンドラ等）から
 * useRuntimeConfig() / useAuthStore() を呼ぶと Nuxt インスタンスが未解決になる落とし穴が
 * あるため、setup 時に capture したものを必ず渡すこと。
 *
 * 返り値は 3 状態（TokenRefreshResult）。呼び出し側は 'transient' でログアウトしないこと。
 */
export function performTokenRefresh(
  config: ReturnType<typeof useRuntimeConfig>,
  authStore: ReturnType<typeof useAuthStore>,
): Promise<TokenRefreshResult> {
  // 二重リフレッシュ防止
  if (refreshPromise) {
    return refreshPromise
  }

  refreshPromise = (async (): Promise<TokenRefreshResult> => {
    // ofetch の timeout オプションに依存せず、確実に abort できるよう自前の
    // AbortController + setTimeout で 15 秒のハング保険を張る。
    const controller = new AbortController()
    const timer = setTimeout(() => controller.abort(), REFRESH_TIMEOUT_MS)
    try {
      // バックエンドは refresh_token Cookie を読むため、body への refreshToken 送信は不要。
      // credentials: 'include' で Cookie が自動送信される。
      const data = await ofetch<{ data: { accessToken: string; refreshToken: string } }>(
        '/api/v1/auth/refresh',
        {
          baseURL: resolveApiBaseUrl(config),
          method: 'POST',
          credentials: 'include',
          signal: controller.signal,
        },
      )
      authStore.setTokens(data.data.accessToken, data.data.refreshToken)
      return 'refreshed'
    }
    catch (error) {
      // 401/403/400 ＝ refresh_token が無効な本物の認証失敗。
      // 400: revoke 済みトークン（パスワード変更・退会・全デバイスログアウト後）でも返される。
      // それ以外（timeout/abort/ネットワークエラー/レスポンス無し/5xx）は一時的とみなし、
      // ログアウトさせない（回線が遅いだけのユーザーを誤ってログアウトさせないため）。
      const status = (error as { response?: { status?: number } })?.response?.status
      if (status === 400 || status === 401 || status === 403) {
        return 'auth_failed'
      }
      return 'transient'
    }
    finally {
      clearTimeout(timer)
      refreshPromise = null
    }
  })()

  return refreshPromise
}

/**
 * access_token の先回り（proactive）リフレッシュタイマーの武装遅延を計算する純粋関数。
 *
 * expiresAtMs（失効時刻）から bufferMs（安全マージン）を差し引いた時刻までの残り時間を返す。
 * 既に過去/バッファ以内の場合は負の遅延にせず 0（即時実行）を返す（AC-4）。
 */
export function computeProactiveRefreshDelayMs(
  expiresAtMs: number,
  nowMs: number,
  bufferMs: number,
): number {
  return Math.max(0, expiresAtMs - nowMs - bufferMs)
}

// 失効の何 ms 前に先回りリフレッシュを発火するかの安全マージン。
const PROACTIVE_REFRESH_BUFFER_MS = 60_000
// 先回りリフレッシュが失敗した場合（transient / auth_failed 等・新しい expiry が得られない）の
// 再武装遅延。リアクティブ 401 ノイズに戻さず、短い間隔でリトライして回復を試みる（AC-7）。
const PROACTIVE_REFRESH_RETRY_DELAY_MS = 30_000

// タイマーハンドルは module-scope で保持する（refreshPromise と同様の single-flight パターン。AC-5）。
let proactiveRefreshTimer: ReturnType<typeof setTimeout> | null = null

/**
 * 武装中の先回りリフレッシュタイマーを解除する（AC-3）。ログアウト時に呼ぶこと。
 */
export function disarmProactiveRefresh(): void {
  if (proactiveRefreshTimer !== null) {
    clearTimeout(proactiveRefreshTimer)
    proactiveRefreshTimer = null
  }
}

/**
 * access_token の先回りリフレッシュタイマーを（再）武装するモジュールスコープ関数。
 *
 * 【呼び出しタイミング】
 * setTokens（ログイン成功時・リフレッシュ成功時の両方。useAuthStore 側でフック）、
 * auth.client プラグインの起動時（認証済みなら）から呼ばれる。
 *
 * 【挙動】
 * - 常に 1 本のみ武装する。呼び出し時に既存タイマーをクリアしてから張り直す（AC-5）。
 * - localStorage の tokenExpiresAt から、失効の PROACTIVE_REFRESH_BUFFER_MS（60秒）前に
 *   発火するよう遅延を計算する。既に過去/バッファ以内なら即時（遅延0）で発火する（AC-4）。
 * - 発火後 performTokenRefresh が 'refreshed' を返せば、setTokens 内で書き込まれた新しい
 *   tokenExpiresAt に基づいて次のタイマーを再武装する（AC-2。セッションが続く限り失効させない）。
 * - 'refreshed' 以外（transient / auth_failed）の場合は諦めず、短い遅延で再武装してリトライする
 *   （AC-7。リアクティブ 401 ノイズに戻さないため）。
 * - SSR では張らない（AC-6。import.meta.client ガード）。
 *
 * config / authStore は performTokenRefresh と同様に引数で受け取る。setTimeout コールバック内で
 * useRuntimeConfig() / useAuthStore() を呼ぶと Nuxt インスタンスが未解決になる落とし穴があるため、
 * 呼び出し元（setTokens・auth.client プラグイン等の同期コンテキスト）で capture したものを渡すこと。
 *
 * 【遅延0（即時）の特別扱い】
 * delayMs が 0 の場合は setTimeout を挟まず、呼び出しと同じ同期フェーズで refresh の fetch を
 * 開始する。auth.client プラグインの起動時武装（リロード直後に Cookie が失効済みのケース）では、
 * 後続のアルファベット順プラグイン（nav-settings.client 等）が先に認証付き API を撃って 401 を
 * 出す前に、refresh のリクエストを確実に先行させる必要がある。setTimeout(fn, 0) はマクロタスクの
 * ため、後続プラグインの同期実行より後回しになってしまいレースに負ける（実機E2E
 * auth-proactive-refresh.spec.ts の PRR-001 が検証するリグレッションの再発になる）。
 */
export function armProactiveRefresh(
  config: ReturnType<typeof useRuntimeConfig>,
  authStore: ReturnType<typeof useAuthStore>,
): void {
  if (!import.meta.client) return

  disarmProactiveRefresh()

  const raw = localStorage.getItem('tokenExpiresAt')
  const expiresAtMs = raw ? Number(raw) : 0
  const delayMs = computeProactiveRefreshDelayMs(expiresAtMs, Date.now(), PROACTIVE_REFRESH_BUFFER_MS)

  const fire = (): void => {
    void (async () => {
      const result = await performTokenRefresh(config, authStore)
      if (result === 'refreshed') {
        // 新しい tokenExpiresAt（setTokens 内で書き込み済み）に基づいて次のタイマーを再武装する。
        armProactiveRefresh(config, authStore)
      }
      else {
        // transient / auth_failed は諦めず短い遅延で再武装し回復を試みる。
        proactiveRefreshTimer = setTimeout(() => {
          armProactiveRefresh(config, authStore)
        }, PROACTIVE_REFRESH_RETRY_DELAY_MS)
      }
    })()
  }

  if (delayMs === 0) {
    fire()
  }
  else {
    proactiveRefreshTimer = setTimeout(fire, delayMs)
  }
}

// 短時間に複数の 5xx が発生した場合のトースト集約
let _errorBatchTimer: ReturnType<typeof setTimeout> | null = null
let _errorBatchCount = 0
let _errorBatchFirst: { status: number; statusText: string; url: string } | null = null

export function useApi() {
  const config = useRuntimeConfig()
  const authStore = useAuthStore()
  const proxyDeskStore = useProxyDeskStore()
  const guardianshipSwitchStore = useGuardianshipSwitchStore()
  const adminImpersonationStore = useAdminImpersonationStore()
  const nuxtApp = useNuxtApp()
  const errorReport = useErrorReport()

  // useI18n() は setup コンテキスト外（イベントハンドラや Pinia アクション）では
  // 呼べないため、useNuxtApp().$i18n 経由でアクセスする。
  const t = (key: string) => nuxtApp.$i18n.t(key)

  const api = ofetch.create({
    baseURL: resolveApiBaseUrl(config),
    // HttpOnly Cookie を自動送信するために credentials: 'include' を設定する。
    // これにより access_token Cookie がすべての API リクエストに付与される。
    credentials: 'include',
    // 401 受信後に onResponseError でトークンをリフレッシュし、onRequest で新トークンを付与して 1 回自動リトライする。
    retry: 1,
    retryStatusCodes: [401],

    onRequest({ options }) {
      if (authStore.accessToken) {
        const headers = new Headers(options.headers)
        headers.set('Authorization', `Bearer ${authStore.accessToken}`)

        // 代理入力モードが有効な場合: 4ヘッダを自動付与
        if (proxyDeskStore.isPinned) {
          headers.set('X-Proxy-For-User-Id', String(proxyDeskStore.pinnedSubjectUserId))
          headers.set('X-Proxy-Consent-Id', String(proxyDeskStore.pinnedConsentId))
          headers.set('X-Proxy-Input-Source', proxyDeskStore.inputSource)
          if (proxyDeskStore.originalStorageLocation) {
            headers.set('X-Proxy-Original-Storage', proxyDeskStore.originalStorageLocation)
          }
        }
        // 後見切替モードが有効な場合: X-Proxy-For-User-Id のみ付与（guardianship 経路）
        // X-Proxy-Consent-Id / X-Proxy-Input-Source は送らない（代理入力とは別経路）
        else if (guardianshipSwitchStore.isActingAs) {
          headers.set('X-Proxy-For-User-Id', String(guardianshipSwitchStore.activeChild!.childUserId))
        }
        // 管理者変身モードが有効な場合: X-Admin-Impersonate-User-Id を付与（F10.1）
        // BE の AdminImpersonationFilter が SecurityContext の principal を対象ユーザー ID に置き換える
        else if (adminImpersonationStore.isImpersonating) {
          headers.set('X-Admin-Impersonate-User-Id', String(adminImpersonationStore.targetUserId))
        }

        options.headers = headers
      }
    },

    async onResponseError({ options, request, response }) {
      // 401: Refresh Token ローテーション
      // バックエンドは未認証リクエストに必ず 401 を返す（SecurityConfig.exceptionHandling 参照）。
      if (response.status === 401) {
        // リトライ済み（2 回目の 401）はリフレッシュ不要。エラーをそのまま伝播させる
        const opts = options as unknown as Record<string, unknown>
        if (opts._tokenRefreshed) return
        opts._tokenRefreshed = true

        if (authStore.user) {
          const result = await performTokenRefresh(config, authStore)
          if (result === 'auth_failed') {
            // refresh_token が無効な本物の認証失敗。ログアウトして /login へ誘導する。
            // reason=session_expired を付与し、ログイン画面でセッション失効の案内を表示する。
            await authStore.logout({ reason: 'session_expired' })
            // throw してリトライを中断する（リフレッシュ失敗 = ログアウト済み）
            throw new Error('token_refresh_failed')
          }
          if (result === 'transient') {
            // timeout / ネットワーク / 5xx の一時的失敗。回線が遅いだけのユーザーを
            // 誤ってログアウトさせないため、ログアウトせず元のエラーをそのまま伝播させる。
            // throw すると ofetch はリトライせず、呼び出し元が 401 を受け取って再試行できる。
            throw new Error('token_refresh_transient')
          }
          // 'refreshed': throw しない → ofetch が onRequest で新トークンを付与して 1 回リトライ
          return
        }
        else {
          await navigateTo('/login')
          // throw してリトライを中断する
          throw new Error('not_authenticated')
        }
      }

      // 403: 認証済みだが権限不足の場合（Forbidden）。
      // ログアウトせず、呼び出し元でエラーを処理する（例: 「アクセス権がありません」表示）。
      // 未認証状態（user が null）での 403 は想定外だが、フォールバックとしてログインへ誘導する。
      if (response.status === 403 && !authStore.user) {
        await navigateTo('/login')
        return
      }

      // 5xx: トースト集約 + エラー報告
      if (response.status >= 500) {
        const requestId = response.headers.get('X-Request-ID') ?? undefined
        const apiUrl = typeof request === 'string' ? request : (request?.toString() ?? '')

        // エラー報告は毎回送信
        errorReport.capture(new Error(`HTTP ${response.status} ${response.statusText}`), {
          apiUrl,
          statusCode: response.status,
          requestId,
        })

        // トーストは 500ms 以内の複数エラーをまとめて1件に集約
        _errorBatchCount++
        if (_errorBatchFirst === null) {
          _errorBatchFirst = {
            status: response.status,
            statusText: response.statusText,
            url: apiUrl,
          }
        }
        if (_errorBatchTimer === null) {
          _errorBatchTimer = setTimeout(() => {
            const toast = useNuxtApp().$toast as
              | { add: (opts: Record<string, unknown>) => void }
              | undefined
            if (toast) {
              toast.add({
                severity: 'error',
                summary: t('error.server'),
                detail:
                  _errorBatchCount > 1
                    ? `${_errorBatchCount}件のサーバーエラーが発生しました`
                    : t('error.server_retry'),
                life: 5000,
              })
            }
            _errorBatchCount = 0
            _errorBatchFirst = null
            _errorBatchTimer = null
          }, 500)
        }
      }
    },
  })

  return api
}
