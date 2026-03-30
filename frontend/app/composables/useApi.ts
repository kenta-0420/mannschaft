import { ofetch } from 'ofetch'

let refreshPromise: Promise<boolean> | null = null

export function useApi() {
  const config = useRuntimeConfig()
  const authStore = useAuthStore()
  const { t } = useI18n()

  const api = ofetch.create({
    baseURL: config.public.apiBase as string,

    onRequest({ options }) {
      if (authStore.accessToken) {
        const headers = new Headers(options.headers)
        headers.set('Authorization', `Bearer ${authStore.accessToken}`)
        options.headers = headers
      }
    },

    async onResponseError({ response }) {
      // 401: Refresh Token ローテーション
      if (response.status === 401 && authStore.refreshToken) {
        const success = await refreshAccessToken()
        if (!success) {
          authStore.logout()
        }
        // リトライは呼び出し元で行う（ofetch.create の onResponseError では戻り値不可）
        return
      }

      // 5xx: トースト通知
      if (response.status >= 500) {
        const toast = useNuxtApp().$toast as
          | { add: (opts: Record<string, unknown>) => void }
          | undefined
        if (toast) {
          toast.add({
            severity: 'error',
            summary: t('common.error.server'),
            detail: t('common.error.server_retry'),
            life: 5000,
          })
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
