import { ofetch } from 'ofetch'

let refreshPromise: Promise<boolean> | null = null

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
          const success = await refreshAccessToken()
          if (!success) {
            await authStore.logout()
            // throw してリトライを中断する（リフレッシュ失敗 = ログアウト済み）
            throw new Error('token_refresh_failed')
          }
          // throw しない → ofetch が onRequest で新トークンを付与して 1 回リトライ
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

  async function refreshAccessToken(): Promise<boolean> {
    // 二重リフレッシュ防止
    if (refreshPromise) {
      return refreshPromise
    }

    refreshPromise = (async () => {
      try {
        // バックエンドは refresh_token Cookie を読むため、body への refreshToken 送信は不要。
        // credentials: 'include' で Cookie が自動送信される。
        const data = await ofetch<{ data: { accessToken: string; refreshToken: string } }>(
          '/api/v1/auth/refresh',
          {
            baseURL: resolveApiBaseUrl(config),
            method: 'POST',
            credentials: 'include',
          },
        )
        authStore.setTokens(data.data.accessToken, data.data.refreshToken)
        return true
      } catch {
        return false
      } finally {
        refreshPromise = null
      }
    })()

    return refreshPromise
  }

  return api
}
