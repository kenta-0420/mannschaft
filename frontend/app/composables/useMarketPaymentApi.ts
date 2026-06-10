import type {
  OnboardingLinkRequest,
  OnboardingLinkResponse,
  ConnectStatusResponse,
  ScopeKind,
  MarketRefundRequest,
  MarketRefundResponse,
  RecruitmentPaymentResponse,
} from '~/types/marketPayment'

/**
 * F22.1 市の謝礼決済 API クライアント。
 *
 * BE 実在エンドポイントのみに配線する（recon 済み・casing は BE camelCase と 1:1）:
 *   - POST /api/v1/payment/connect/onboarding-link                                  （Connect onboarding リンク発行）
 *   - GET  /api/v1/payment/connect/status                                           （Connect 状態照会）
 *   - GET  /api/v1/payment/escrow/recruitment/{listingId}/{participantId}/payment-intent
 *                                                                                    （札主の決済確認・第二陣 #1443）
 *   - GET  /api/v1/payment/escrow/{id}                                              （エスクロー状態照会・第二陣 #1443）
 *   - POST /api/v1/payment/escrow/{id}/refund                                       （エスクロー返金・受取側 ADMIN）
 *
 * 第二陣（EscrowPaymentController）で札主の決済確認 EP が実装されたため、従来「BE 欠落」として
 * 対象外にしていた謝礼決済確認 UI（札主のカード confirm）を本配線で有効化する。
 * clientSecret は GET payment-intent が支払者本人 × PENDING_CONFIRMATION 時のみ返す。
 *
 * BE 欠落（未実装・報告対象）:
 *   受取側 scope（応じ手/チーム/組織）が「受け取った謝礼エスクローの一覧」を取得する EP は存在しない。
 *   返金管理 UI は単一エスクロー照会（recruitment payment-intent で escrowTransactionId を解決）で
 *   最小配線する（一覧 EP は別途 BE 実装が必要・誤魔化さず報告・根治原則）。
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

  /**
   * 札主の決済確認ビューを取得する（謝礼エスクローの clientSecret＋手数料内訳＋状態・第二陣 #1443）。
   * clientSecret は支払者本人 × PENDING_CONFIRMATION 時のみ非 null。
   * escrow 未準備（成立リスナ @Async 遅延）の場合 BE は 404 を返すため、呼び出し側でリトライ案内する。
   */
  async function getRecruitmentPaymentIntent(listingId: number, participantId: number) {
    return api<{ data: RecruitmentPaymentResponse }>(
      `/api/v1/payment/escrow/recruitment/${listingId}/${participantId}/payment-intent`,
    )
  }

  /** エスクロー状態を照会する（支払者本人=clientSecret 含む / 受取側 ADMIN=状態・金額のみ）。 */
  async function getEscrow(escrowId: string) {
    return api<{ data: RecruitmentPaymentResponse }>(
      `/api/v1/payment/escrow/${escrowId}`,
    )
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
    getRecruitmentPaymentIntent,
    getEscrow,
    refund,
  }
}
