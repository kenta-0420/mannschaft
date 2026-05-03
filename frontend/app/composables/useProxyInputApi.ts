import type {
  ProxyInputConsent,
  ProxyInputRecord,
  CreateProxyInputConsentRequest,
  RevokeProxyInputConsentRequest,
  ScanUploadUrlResponse,
  ScanDownloadUrlResponse,
} from '~/types/proxy-input'

/** ページネーション付きレスポンス */
interface PagedResponse<T> {
  data: T[]
  totalElements: number
}

/**
 * 代理入力（Proxy Input）バックエンド API composable。
 * 同意書の登録・承認・撤回、履歴照会、スキャン画像の S3 presigned URL 発行を担う。
 */
export function useProxyInputApi() {
  const api = useApi()

  /** 自分が代理人として持つ有効同意書一覧（デスク起動時に使用） */
  async function getActiveConsents(): Promise<ProxyInputConsent[]> {
    return api<ProxyInputConsent[]>('/api/v1/proxy-input-consents/active')
  }

  /** 組織単位の同意書一覧（管理者用） */
  async function getConsentsByOrg(orgId: number): Promise<ProxyInputConsent[]> {
    return api<ProxyInputConsent[]>(`/api/v1/organizations/${orgId}/proxy-input-consents`)
  }

  /** 同意書登録 */
  async function createConsent(
    orgId: number,
    request: CreateProxyInputConsentRequest,
  ): Promise<ProxyInputConsent> {
    return api<ProxyInputConsent>(`/api/v1/organizations/${orgId}/proxy-input-consents`, {
      method: 'POST',
      body: request,
    })
  }

  /** 同意書承認 */
  async function approveConsent(id: number): Promise<ProxyInputConsent> {
    return api<ProxyInputConsent>(`/api/v1/proxy-input-consents/${id}/approve`, {
      method: 'PATCH',
    })
  }

  /** 同意書撤回 */
  async function revokeConsent(
    id: number,
    request: RevokeProxyInputConsentRequest,
  ): Promise<ProxyInputConsent> {
    return api<ProxyInputConsent>(`/api/v1/proxy-input-consents/${id}/revoke`, {
      method: 'PATCH',
      body: request,
    })
  }

  /** 代理入力履歴（監査用） */
  async function getRecords(params?: {
    subjectUserId?: number
    page?: number
    size?: number
  }): Promise<PagedResponse<ProxyInputRecord>> {
    const qs = params
      ? new URLSearchParams(
          Object.fromEntries(
            Object.entries(params)
              .filter(([, v]) => v !== undefined)
              .map(([k, v]) => [k, String(v)]),
          ),
        ).toString()
      : ''
    return api<PagedResponse<ProxyInputRecord>>(
      `/api/v1/proxy-input-records${qs ? `?${qs}` : ''}`,
    )
  }

  /** スキャン画像アップロード用 presigned URL 発行 */
  async function getScanUploadUrl(orgId: number): Promise<ScanUploadUrlResponse> {
    return api<ScanUploadUrlResponse>(
      `/api/v1/organizations/${orgId}/proxy-input-consents/scan-upload-url`,
      {
        method: 'POST',
      },
    )
  }

  /** スキャン画像ダウンロード用 presigned URL 発行 */
  async function getScanDownloadUrl(id: number): Promise<ScanDownloadUrlResponse> {
    return api<ScanDownloadUrlResponse>(`/api/v1/proxy-input-consents/${id}/scan-download-url`)
  }

  return {
    getActiveConsents,
    getConsentsByOrg,
    createConsent,
    approveConsent,
    revokeConsent,
    getRecords,
    getScanUploadUrl,
    getScanDownloadUrl,
  }
}
