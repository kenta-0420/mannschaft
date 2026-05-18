import type {
  AdPreferencesResponse,
  UpdateAdPreferencesRequest,
} from '~/types/adPreferences'

/**
 * F09.17 受信者向け広告受信設定 API クライアント
 *
 * - GET `/api/v1/me/ad-preferences` で初期表示用
 * - PUT `/api/v1/me/ad-preferences` で保存（部分更新）
 * - PUT `/api/v1/me/ad-preferences` + `rotateUnsubscribeToken=true` で
 *   既存メール内の unsubscribe リンクを一括無効化（unsubscribe_token_version をインクリメント）
 */
export function useAdPreferencesApi() {
  const api = useApi()

  async function getPreferences() {
    return api<{ data: AdPreferencesResponse }>('/api/v1/me/ad-preferences')
  }

  async function updatePreferences(body: UpdateAdPreferencesRequest) {
    return api<{ data: AdPreferencesResponse }>('/api/v1/me/ad-preferences', {
      method: 'PUT',
      body,
    })
  }

  async function rotateUnsubscribeToken() {
    return updatePreferences({ rotateUnsubscribeToken: true })
  }

  return {
    getPreferences,
    updatePreferences,
    rotateUnsubscribeToken,
  }
}
