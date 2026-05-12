import { ofetch, type FetchError } from 'ofetch'
import type { PublicActivityResponse } from '~/types/activity'

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
          baseURL: config.public.apiBase as string,
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
