import type {
  OnboardingLinkRequest,
  OnboardingLinkResponse,
  ConnectStatusResponse,
  ScopeKind,
  MarketRefundRequest,
  MarketRefundResponse,
  RecruitmentPaymentResponse,
  ReceivedEscrowPage,
  EscrowStatus,
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
 *   - GET  /api/v1/payment/escrow/received                                          （受取側のエスクロー一覧・Wave A #1452）
 *   - POST /api/v1/payment/escrow/{id}/refund                                       （エスクロー返金・受取側 ADMIN）
 *
 * 第二陣（EscrowPaymentController）で札主の決済確認 EP が実装されたため、従来「BE 欠落」として
 * 対象外にしていた謝礼決済確認 UI（札主のカード confirm）を本配線で有効化する。
 * clientSecret は GET payment-intent が支払者本人 × PENDING_CONFIRMATION 時のみ返す。
 *
 * フォロー Wave A（#1452）で受取側のエスクロー一覧 EP（GET /escrow/received）が実装されたため、
 * 受取側 ADMIN 向けの返金管理画面（一覧→返金）を本格化できる。一覧は scopeKind/scopeId で受取 scope を
 * 指定し、PagedResponse<ReceivedEscrowResponse>（camelCase・clientSecret 非含有）を返す。
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

  /**
   * 受取側（payee）が受け取ったエスクロー一覧を取得する（Wave A #1452）。
   *
   * scopeKind/scopeId で受取 scope を指定する（USER=本人 / TEAM=ADMIN / ORG=ADMIN・認可と IDOR は BE が担保）。
   * status は任意（未指定で全状態）。レスポンスは PagedResponse<ReceivedEscrowResponse>（camelCase・
   * clientSecret 非含有）。返却型は ReceivedEscrowPage（{ data, meta:{total,page,size,totalPages} }）。
   */
  async function getReceivedEscrows(
    scopeKind: ScopeKind,
    scopeId: number,
    opts: { status?: EscrowStatus | null, page?: number, size?: number } = {},
  ) {
    const query: Record<string, string | number> = {
      scopeKind,
      scopeId,
      page: opts.page ?? 0,
      size: opts.size ?? 20,
    }
    if (opts.status != null) {
      query.status = opts.status
    }
    return api<ReceivedEscrowPage>('/api/v1/payment/escrow/received', { query })
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
    getReceivedEscrows,
    getEscrow,
    refund,
  }
}
