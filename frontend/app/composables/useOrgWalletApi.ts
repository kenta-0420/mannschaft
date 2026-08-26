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
  BalanceEventRequest,
  BalanceEventResponse,
  CreateOrgProviderRequest,
  CustomerQrResponse,
  OrgPointCardProvider,
  PageResponse,
  ResolveTokenResponse,
  StampEventResponse,
  StampRequest,
  UpdateOrgProviderRequest,
} from '~/types/orgPointCard'

export function useOrgWalletApi(orgId: () => string) {
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

  // ─────────────────────────────────────────────
  // Phase 3 — 一時トークン resolve + 残高型（CHARGE/SPENT/REFUND）
  // ─────────────────────────────────────────────

  /**
   * 顧客が発行した 5 分 TTL 一時トークンを resolve して cardId を特定する。
   *
   * <p>Valkey から GETDEL で 1 回限り消費。期限切れ・使用済・不存在は全て
   * 404 + {@code POINT_CARD_019 TOKEN_NOT_FOUND} に統一される（情報漏洩防止）。
   */
  async function resolveByToken(token: string): Promise<ResolveTokenResponse> {
    const res = await api<{ data: ResolveTokenResponse }>(
      `${base()}/resolve-by-token`,
      { method: 'POST', body: { token } },
    )
    return res.data
  }

  /**
   * 残高変動イベントを記録する（CHARGE / SPENT / REFUND）。
   *
   * <p>{@code operationType} で分岐し、内部で監査ログ
   * {@code POINT_CARD_BALANCE_CHARGED/SPENT/REFUNDED} が記録される。
   * {@code amount} は常に正の値で渡す（SPENT は Service 層で負に変換）。
   *
   * <p>主なエラー:
   * <ul>
   *   <li>POINT_CARD_017 — 残高不足（SPENT）</li>
   *   <li>POINT_CARD_018 — 残高上限超過（CHARGE）</li>
   *   <li>POINT_CARD_020 — 累計返金額超過（REFUND）</li>
   *   <li>POINT_CARD_024 — 残高機能凍結中（503 SERVICE_UNAVAILABLE。資金決済法対応）</li>
   * </ul>
   *
   * <p>F18 SELF_ISSUED_BALANCE 凍結（2026-05-17 マスター御裁可）:
   * フロントの runtimeConfig.public.f18BalanceEnabled=false の場合は API 呼び出し自体を
   * 行わず、ローカルでエラーを投げる（無駄な往復を抑止）。バックエンド側も
   * f18.balance.enabled=false で 503 を返すため二重防御となる。
   */
  async function recordBalanceEvent(
    cardId: string,
    body: BalanceEventRequest,
  ): Promise<BalanceEventResponse> {
    const config = useRuntimeConfig()
    if (!config.public.f18BalanceEnabled) {
      // 凍結中: API を叩かずローカルで弾く（バックエンドも 503 で根治治療）
      throw new Error('F18_BALANCE_DISABLED')
    }
    const res = await api<{ data: BalanceEventResponse }>(
      `${base()}/${cardId}/balance-events`,
      { method: 'POST', body },
    )
    return res.data
  }

  /**
   * 組織内の残高変動履歴を取得する（新着順、Pageable）。
   *
   * <p>このエンドポイントは ApiResponse でラップせず Spring Data {@code Page<T>} を直接返す。
   */
  async function listOrgBalanceEvents(
    params?: { providerId?: string; page?: number; size?: number },
  ): Promise<PageResponse<BalanceEventResponse>> {
    return await api<PageResponse<BalanceEventResponse>>(`${base()}/balance-events`, {
      params,
    })
  }

  /** 単一カードの残高変動履歴を取得する（新着順）。 */
  async function listCardBalanceEvents(cardId: string): Promise<BalanceEventResponse[]> {
    const res = await api<{ data: BalanceEventResponse[] }>(
      `${base()}/${cardId}/balance-events`,
    )
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
    resolveByToken,
    recordBalanceEvent,
    listOrgBalanceEvents,
    listCardBalanceEvents,
  }
}
