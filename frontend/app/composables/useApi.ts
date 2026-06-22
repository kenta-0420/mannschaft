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
 * 起動時の先回りリフレッシュ（auth.client プラグイン）と interceptor の 401 リカバリの
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
      // 401/403 ＝ refresh_token が無効な本物の認証失敗。それ以外（timeout/abort/
      // ネットワークエラー/レスポンス無し/5xx）は一時的とみなし、ログアウトさせない。
      const status = (error as { response?: { status?: number } })?.response?.status
      if (status === 401 || status === 403) {
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

// 短時間に複数の 5xx が発生した場合のトースト集約
let _errorBatchTimer: ReturnType<typeof setTimeout> | null = null
let _errorBatchCount = 0
let _errorBatchFirst: { status: number; statusText: string; url: string } | null = null

export function useApi() {
  const config = useRuntimeConfig()
  const authStore = useAuthStore()
  const proxyDeskStore = useProxyDeskStore()
  const guardianshipSwitchStore = useGuardianshipSwitchStore()
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
            await authStore.logout()
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
