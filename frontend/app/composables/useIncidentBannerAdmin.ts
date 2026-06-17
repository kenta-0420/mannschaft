/**
 * F12.5: システム管理者向け障害告知バナー管理 API クライアント。
 *
 * バックエンド: {@code /api/v1/system-admin/incident-banners} 配下。
 * ROLE_SYSTEM_ADMIN ロールでのみアクセス可能（BE側で自動認可）。
 *
 * 提供メソッド:
 *  - {@code fetchList(page, size)} — バナー一覧取得（ページネーション）
 *  - {@code fetchDetail(id)} — バナー詳細取得
 *  - {@code createBanner(req)} — バナー作成
 *  - {@code updateBanner(id, req)} — バナー更新
 *  - {@code publishBanner(id)} — バナーを公開
 *  - {@code unpublishBanner(id)} — バナーを非公開
 *  - {@code deleteBanner(id)} — バナー削除
 *  - {@code fetchSuggestions()} — 検知候補リスト取得
 */
import type { components } from '~/types/generated/index'

// 生成型エイリアス
export type IncidentBannerRequest = components['schemas']['IncidentBannerRequest']
export type IncidentBannerResponse = components['schemas']['IncidentBannerResponse']
export type IncidentSuggestionResponse = components['schemas']['IncidentSuggestionResponse']
export type TranslationDto = components['schemas']['TranslationDto']

export interface IncidentBannerPagedResponse {
  data?: IncidentBannerResponse[]
  meta?: {
    page?: number
    size?: number
    totalElements?: number
    totalPages?: number
  }
}

const BASE = '/api/v1/system-admin/incident-banners'

export function useIncidentBannerAdmin() {
  const api = useApi()

  /**
   * バナー一覧を取得する。
   */
  async function fetchList(page = 0, size = 20): Promise<IncidentBannerPagedResponse> {
    return api<IncidentBannerPagedResponse>(`${BASE}?page=${page}&size=${size}`)
  }

  /**
   * 指定 ID のバナー詳細を取得する。
   */
  async function fetchDetail(id: string): Promise<{ data?: IncidentBannerResponse }> {
    return api<{ data?: IncidentBannerResponse }>(`${BASE}/${encodeURIComponent(id)}`)
  }

  /**
   * バナーを新規作成する（下書き状態）。
   */
  async function createBanner(req: IncidentBannerRequest): Promise<{ data?: IncidentBannerResponse }> {
    return api<{ data?: IncidentBannerResponse }>(BASE, {
      method: 'POST',
      body: req,
    })
  }

  /**
   * 指定 ID のバナーを更新する。
   */
  async function updateBanner(
    id: string,
    req: IncidentBannerRequest,
  ): Promise<{ data?: IncidentBannerResponse }> {
    return api<{ data?: IncidentBannerResponse }>(`${BASE}/${encodeURIComponent(id)}`, {
      method: 'PUT',
      body: req,
    })
  }

  /**
   * バナーを公開する。
   */
  async function publishBanner(id: string): Promise<{ data?: IncidentBannerResponse }> {
    return api<{ data?: IncidentBannerResponse }>(
      `${BASE}/${encodeURIComponent(id)}/publish`,
      { method: 'POST' },
    )
  }

  /**
   * バナーを非公開にする。
   */
  async function unpublishBanner(id: string): Promise<{ data?: IncidentBannerResponse }> {
    return api<{ data?: IncidentBannerResponse }>(
      `${BASE}/${encodeURIComponent(id)}/unpublish`,
      { method: 'POST' },
    )
  }

  /**
   * バナーを削除する。
   */
  async function deleteBanner(id: string): Promise<void> {
    await api(`${BASE}/${encodeURIComponent(id)}`, { method: 'DELETE' })
  }

  /**
   * 検知候補リストを取得する。
   */
  async function fetchSuggestions(): Promise<{ data?: IncidentSuggestionResponse[] }> {
    return api<{ data?: IncidentSuggestionResponse[] }>(`${BASE}/suggestions`)
  }

  return {
    fetchList,
    fetchDetail,
    createBanner,
    updateBanner,
    publishBanner,
    unpublishBanner,
    deleteBanner,
    fetchSuggestions,
  }
}
