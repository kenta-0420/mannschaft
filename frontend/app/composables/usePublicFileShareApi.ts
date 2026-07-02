import { ofetch } from 'ofetch'
import { resolveApiBaseUrl } from '~/composables/useApiBaseUrl'
import type { PublicSharedFileMeta } from '~/types/filesharing'

/**
 * F05.5 (D) 公開共有リンク（未認証）専用 API。
 *
 * useApi() は Authorization ヘッダ付与・401 時の refresh リトライを行うため、
 * 未ログインで開く /shared/{token} には不適。ここでは素の ofetch を使い、
 * 認証まわりの副作用を持たない公開エンドポイントだけを叩く。
 */
export function usePublicFileShareApi() {
  const config = useRuntimeConfig()
  const baseURL = resolveApiBaseUrl(config)

  /** 公開リンクにアクセスしてファイルメタを取得する。パスワード付きリンクは password を渡す。 */
  async function accessLink(token: string, password?: string) {
    return ofetch<{ data: PublicSharedFileMeta }>(
      `/api/v1/public/file-links/${token}/access`,
      { baseURL, method: 'POST', body: { password: password ?? undefined } },
    )
  }

  /** 公開リンクのダウンロード URL を発行する（DL 許可リンクのみ）。 */
  async function requestDownloadUrl(token: string, password?: string) {
    return ofetch<{ data: { downloadUrl: string } }>(
      `/api/v1/public/file-links/${token}/download-url`,
      { baseURL, method: 'POST', body: { password: password ?? undefined } },
    )
  }

  return { accessLink, requestDownloadUrl }
}
