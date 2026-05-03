import { ofetch } from 'ofetch'

let refreshPromise: Promise<boolean> | null = null

// 短時間に複数の 5xx が発生した場合のトースト集約
let _errorBatchTimer: ReturnType<typeof setTimeout> | null = null
let _errorBatchCount = 0
let _errorBatchFirst: { status: number; statusText: string; url: string } | null = null

export function useApi() {
  const config = useRuntimeConfig()
  const authStore = useAuthStore()
  const nuxtApp = useNuxtApp()
  const errorReport = useErrorReport()

  // useI18n() は setup コンテキスト外（イベントハンドラや Pinia アクション）では
  // 呼べないため、useNuxtApp().$i18n 経由でアクセスする。
  const t = (key: string) => nuxtApp.$i18n.t(key)

  const api = ofetch.create({
    baseURL: config.public.apiBase as string,

    onRequest({ options }) {
      if (authStore.accessToken) {
        const headers = new Headers(options.headers)
        headers.set('Authorization', `Bearer ${authStore.accessToken}`)

        // 代理入力モードが有効な場合: 4ヘッダを自動付与
        const proxyDeskStore = useProxyDeskStore()
        if (proxyDeskStore.isPinned) {
          headers.set('X-Proxy-For-User-Id', String(proxyDeskStore.pinnedSubjectUserId))
          headers.set('X-Proxy-Consent-Id', String(proxyDeskStore.pinnedConsentId))
          headers.set('X-Proxy-Input-Source', proxyDeskStore.inputSource)
          if (proxyDeskStore.originalStorageLocation) {
            headers.set('X-Proxy-Original-Storage', proxyDeskStore.originalStorageLocation)
          }
        }

        options.headers = headers
      }
    },

    async onResponseError({ request, response }) {
      // 401: Refresh Token ローテーション
      if (response.status === 401 && authStore.refreshToken) {
        const success = await refreshAccessToken()
        if (!success) {
          authStore.logout()
        }
        // リトライは呼び出し元で行う（ofetch.create の onResponseError では戻り値不可）
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
        const data = await ofetch<{ data: { accessToken: string; refreshToken: string } }>(
          '/api/v1/auth/refresh',
          {
            baseURL: config.public.apiBase as string,
            method: 'POST',
            body: { refreshToken: authStore.refreshToken },
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
