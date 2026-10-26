import type {
  UserAdPreferences,
  UpdateAdPreferencesRequest,
  UnsubscribeRequest,
  UnsubscribeResultResponse,
} from '~/types/adPreferences'

/**
 * F09.17 受信者向け広告受信設定 API クライアント
 *
 * - GET `/api/v1/me/ad-preferences` で初期表示用
 * - PUT `/api/v1/me/ad-preferences` で保存（部分更新）
 * - PUT `/api/v1/me/ad-preferences` + `rotateUnsubscribeTokens=true` で
 *   既存メール内の unsubscribe リンクを一括無効化（unsubscribe_token_version をインクリメント）
 */
export function useAdPreferencesApi() {
  const api = useApi()

  async function getPreferences() {
    return api<{ data: UserAdPreferences }>('/api/v1/me/ad-preferences')
  }

  async function updatePreferences(body: UpdateAdPreferencesRequest) {
    return api<{ data: UserAdPreferences }>('/api/v1/me/ad-preferences', {
      method: 'PUT',
      body,
    })
  }

  async function rotateUnsubscribeTokens() {
    return updatePreferences({ rotateUnsubscribeTokens: true })
  }

  /**
   * F09.17 残課題 4 — 公開 unsubscribe SPA からのチャネル選択 OFF。
   *
   * <p>このエンドポイントは認証不要（JWT による本人特定）。{@code useApi} 経由で呼ぶと
   * Authorization ヘッダが未ログイン時には付かないだけなので、認証不要ページからも使用可能。</p>
   */
  async function submitUnsubscribe(body: UnsubscribeRequest) {
    return api<UnsubscribeResultResponse>('/api/v1/ads/unsubscribe', {
      method: 'POST',
      body,
    })
  }

  return {
    getPreferences,
    updatePreferences,
    rotateUnsubscribeTokens,
    submitUnsubscribe,
  }
}
