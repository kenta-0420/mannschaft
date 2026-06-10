import type {
  OnboardingLinkRequest,
  OnboardingLinkResponse,
  ConnectStatusResponse,
  ScopeKind,
  MarketRefundRequest,
  MarketRefundResponse,
} from '~/types/marketPayment'

/**
 * F22.1 市の謝礼決済 API クライアント。
 *
 * BE 実在エンドポイントのみに配線する（recon 済み・casing は BE camelCase と 1:1）:
 *   - POST /api/v1/payment/connect/onboarding-link  （Connect onboarding リンク発行）
 *   - GET  /api/v1/payment/connect/status           （Connect 状態照会）
 *   - POST /api/v1/payment/escrow/{id}/refund        （エスクロー返金・受取側 ADMIN）
 *
 * BE 欠落（実装しない）:
 *   札主が pending な謝礼決済の clientSecret を取得して Stripe Elements で confirm する
 *   HTTP エンドポイントは BE に存在しない。AuthorizeChargeResult.clientSecret は
 *   RecruitmentChargeAuthorizationListener（@Async / system 経路）でのみ生成され、
 *   どの Controller からも返却されない。よって謝礼決済確認 UI（札主のカード confirm）は
 *   本配線の対象外とする（誤魔化さず欠落として報告・根治原則）。
 */
export function useMarketPaymentApi() {
  const api = useApi()

  /** Connect onboarding リンクを発行する（受取側本人 / TEAM・ORG scope ADMIN）。 */
  async function createOnboardingLink(body: OnboardingLinkRequest) {
    return api<{ data: OnboardingLinkResponse }>('/api/v1/payment/connect/onboarding-link', {
      method: 'POST',
      body,
    })
  }

  /**
   * Connect 状態を取得する。
   * USER 時は scopeId が無視され本人に固定される（BE 側で処理）ため省略してよい。
   */
  async function getConnectStatus(scopeKind: ScopeKind, scopeId?: number | null) {
    const query: Record<string, string | number> = { scopeKind }
    if (scopeId != null) {
      query.scopeId = scopeId
    }
    return api<{ data: ConnectStatusResponse }>('/api/v1/payment/connect/status', { query })
  }

  /** 返金 / 与信取消を行う（受取側 ADMIN・feeBearer=PAYER|PAYEE の 2モード）。 */
  async function refund(escrowId: string, body: MarketRefundRequest) {
    return api<{ data: MarketRefundResponse }>(
      `/api/v1/payment/escrow/${escrowId}/refund`,
      { method: 'POST', body },
    )
  }

  return {
    createOnboardingLink,
    getConnectStatus,
    refund,
  }
}
