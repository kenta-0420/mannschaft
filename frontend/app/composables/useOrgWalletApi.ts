/**
 * F18 Phase 2 — 組織（店主）スコープ API クライアント composable。
 *
 * <p>設計書: docs/features/F18_point_card_wallet.md §6 / §12
 *
 * <p>店主ダッシュボード（3A）で使う 9 エンドポイントを束ねる。
 * <ul>
 *   <li>GET    /api/v1/organizations/{orgId}/point-cards/providers?active=...</li>
 *   <li>POST   /api/v1/organizations/{orgId}/point-cards/providers</li>
 *   <li>GET    /api/v1/organizations/{orgId}/point-cards/providers/{providerId}</li>
 *   <li>PATCH  /api/v1/organizations/{orgId}/point-cards/providers/{providerId}</li>
 *   <li>DELETE /api/v1/organizations/{orgId}/point-cards/providers/{providerId}</li>
 *   <li>GET    /api/v1/organizations/{orgId}/point-cards/providers/{providerId}/customer-qr</li>
 *   <li>POST   /api/v1/organizations/{orgId}/point-cards/{cardId}/stamps</li>
 *   <li>GET    /api/v1/organizations/{orgId}/point-cards/stamps（{@code Page<>} 直返）</li>
 *   <li>GET    /api/v1/organizations/{orgId}/point-cards/{cardId}/stamps</li>
 * </ul>
 *
 * <p>レスポンスは {@code Page<>} 直返の 1 本を除き ApiResponse でラップされているため、
 * 各メソッドで {@code .data} を剥がして使いやすい型を返す。
 *
 * <p>{@code orgId} は ref から取り出すクロージャを引数に受け取る（route.params 変化に追随する目的）。
 */
import type {
  CreateOrgProviderRequest,
  CustomerQrResponse,
  OrgPointCardProvider,
  PageResponse,
  StampEventResponse,
  StampRequest,
  UpdateOrgProviderRequest,
} from '~/types/orgPointCard'

export function useOrgWalletApi(orgId: () => number) {
  const api = useApi()
  const base = () => `/api/v1/organizations/${orgId()}/point-cards`

  // ─────────────────────────────────────────────
  // Providers
  // ─────────────────────────────────────────────

  /**
   * 組織配下のプロバイダー一覧を取得する。
   *
   * @param activeOnly true なら {@code is_active=true} のみ（既定）
   */
  async function listProviders(activeOnly = true): Promise<OrgPointCardProvider[]> {
    const res = await api<{ data: OrgPointCardProvider[] }>(
      `${base()}/providers?active=${activeOnly}`,
    )
    return res.data
  }

  /** プロバイダー詳細を取得する。 */
  async function getProvider(providerId: string): Promise<OrgPointCardProvider> {
    const res = await api<{ data: OrgPointCardProvider }>(
      `${base()}/providers/${providerId}`,
    )
    return res.data
  }

  /**
   * プロバイダーを新規発行する。
   *
   * <p>上限超過時は 409 + {@code POINT_CARD_010} {@code PROVIDER_LIMIT_EXCEEDED}（20 個/組織）。
   */
  async function createProvider(body: CreateOrgProviderRequest): Promise<OrgPointCardProvider> {
    const res = await api<{ data: OrgPointCardProvider }>(`${base()}/providers`, {
      method: 'POST',
      body,
    })
    return res.data
  }

  /** プロバイダーを部分更新する。 */
  async function updateProvider(
    providerId: string,
    body: UpdateOrgProviderRequest,
  ): Promise<OrgPointCardProvider> {
    const res = await api<{ data: OrgPointCardProvider }>(
      `${base()}/providers/${providerId}`,
      { method: 'PATCH', body },
    )
    return res.data
  }

  /**
   * プロバイダーを停止する（{@code is_active=false} に切替、物理削除はしない）。
   *
   * <p>既存顧客のカードはそのまま残り、新規追加のみ不可になる。
   */
  async function deactivateProvider(providerId: string): Promise<void> {
    await api(`${base()}/providers/${providerId}`, { method: 'DELETE' })
  }

  /**
   * 顧客向け QR コード情報（deepLink + webUrl）を取得する。
   *
   * <p>QR 画像はこの URL をフロントの qrcode ライブラリでエンコードして描画する。
   */
  async function getCustomerQr(providerId: string): Promise<CustomerQrResponse> {
    const res = await api<{ data: CustomerQrResponse }>(
      `${base()}/providers/${providerId}/customer-qr`,
    )
    return res.data
  }

  // ─────────────────────────────────────────────
  // Stamps
  // ─────────────────────────────────────────────

  /**
   * カードにスタンプ押印する。
   *
   * <p>POINT_CARD_012/013/014 のエラーが発生する可能性あり（プロバイダー種別不整合・delta=0 等）。
   */
  async function stamp(cardId: string, body: StampRequest): Promise<StampEventResponse> {
    const res = await api<{ data: StampEventResponse }>(`${base()}/${cardId}/stamps`, {
      method: 'POST',
      body,
    })
    return res.data
  }

  /**
   * 組織内の押印履歴を取得する（新着順、Pageable）。
   *
   * <p>このエンドポイントだけは ApiResponse でラップせず Spring Data {@code Page<T>} を直接返す。
   *
   * @param params providerId / page / size を任意指定
   */
  async function listOrgStamps(
    params?: { providerId?: string; page?: number; size?: number },
  ): Promise<PageResponse<StampEventResponse>> {
    return await api<PageResponse<StampEventResponse>>(`${base()}/stamps`, {
      params,
    })
  }

  /** 単一カードの押印履歴を取得する（新着順）。 */
  async function listCardStamps(cardId: string): Promise<StampEventResponse[]> {
    const res = await api<{ data: StampEventResponse[] }>(`${base()}/${cardId}/stamps`)
    return res.data
  }

  return {
    listProviders,
    getProvider,
    createProvider,
    updateProvider,
    deactivateProvider,
    getCustomerQr,
    stamp,
    listOrgStamps,
    listCardStamps,
  }
}
