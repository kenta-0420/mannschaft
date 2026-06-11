import { ofetch, type FetchError } from 'ofetch'
import type { PublicActivityResponse } from '~/types/activity'
import { resolveApiBaseUrl } from '~/composables/useApiBaseUrl'

/**
 * 公開活動記録 API Composable
 * 認証不要の公開エンドポイント /api/v1/public/activities/{id} を呼ぶ
 */
export function useActivityPublicApi() {
  const config = useRuntimeConfig()

  /**
   * 公開活動記録を取得する
   * @param id 活動記録 ID
   * @returns 公開活動記録、または 404 の場合は null
   * @throws 404 以外のエラーの場合はスロー
   */
  async function fetchPublicActivity(id: number): Promise<PublicActivityResponse | null> {
    try {
      const res = await ofetch<{ data: PublicActivityResponse }>(
        `/api/v1/public/activities/${id}`,
        {
          // SSR 時は NUXT_INTERNAL_API_BASE（絶対 URL）を優先する。
          // 本番 NUXT_PUBLIC_API_BASE='' では Nitro サーバーが相対パスで BE に到達できないため。
          // 詳細: docs/security/03_security_headers_and_csp.md §4.1
          baseURL: resolveApiBaseUrl(config),
        },
      )
      return res.data
    } catch (error) {
      const fetchError = error as FetchError
      if (fetchError?.response?.status === 404) {
        // PUBLIC 以外の活動記録、または存在しない ID の場合は null を返す
        return null
      }
      // 404 以外のエラー（5xx 等）はスロー
      throw error
    }
  }

  return { fetchPublicActivity }
}
