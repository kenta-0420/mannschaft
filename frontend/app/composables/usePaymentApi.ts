import type {
  PaymentItemResponse,
  MemberPaymentResponse,
  CheckoutSessionResponse,
  PaymentSummaryResponse,
  MyPaymentResponse,
  MemberPaymentReceiptResponse,
  FeeStatementResponse,
  BulkPaymentResponse,
} from '~/types/payment'

export function usePaymentApi() {
  const api = useApi()

  function base(scopeType: 'team' | 'organization', scopeId: string) {
    return scopeType === 'team' ? `/api/v1/teams/${scopeId}` : `/api/v1/organizations/${scopeId}`
  }

  // === Payment Items ===
  async function getPaymentItems(scopeType: 'team' | 'organization', scopeId: string) {
    return api<{ data: PaymentItemResponse[] }>(`${base(scopeType, scopeId)}/payment-items`)
  }
  async function createPaymentItem(
    scopeType: 'team' | 'organization',
    scopeId: string,
    body: Record<string, unknown>,
  ) {
    return api<{ data: PaymentItemResponse }>(`${base(scopeType, scopeId)}/payment-items`, {
      method: 'POST',
      body,
    })
  }
  async function updatePaymentItem(
    scopeType: 'team' | 'organization',
    scopeId: string,
    itemId: number,
    body: Record<string, unknown>,
  ) {
    return api(`${base(scopeType, scopeId)}/payment-items/${itemId}`, { method: 'PATCH', body })
  }
  async function deletePaymentItem(
    scopeType: 'team' | 'organization',
    scopeId: string,
    itemId: number,
  ) {
    return api(`${base(scopeType, scopeId)}/payment-items/${itemId}`, { method: 'DELETE' })
  }

  // === Member Payments ===
  async function getMemberPayments(
    scopeType: 'team' | 'organization',
    scopeId: string,
    itemId: number,
  ) {
    return api<{ data: MemberPaymentResponse[] }>(
      `${base(scopeType, scopeId)}/payment-items/${itemId}/payments`,
    )
  }
  async function recordManualPayment(
    scopeType: 'team' | 'organization',
    scopeId: string,
    itemId: number,
    body: Record<string, unknown>,
  ) {
    return api(`${base(scopeType, scopeId)}/payment-items/${itemId}/payments`, {
      method: 'POST',
      body,
    })
  }
  async function bulkRecordPayment(
    scopeType: 'team' | 'organization',
    scopeId: string,
    itemId: number,
    payments: Array<Record<string, unknown>>,
  ) {
    return api<{ data: BulkPaymentResponse }>(
      `${base(scopeType, scopeId)}/payment-items/${itemId}/payments/bulk`,
      {
        method: 'POST',
        body: { payments },
      },
    )
  }
  async function cancelPayment(
    scopeType: 'team' | 'organization',
    scopeId: string,
    itemId: number,
    paymentId: number,
  ) {
    return api(`${base(scopeType, scopeId)}/payment-items/${itemId}/payments/${paymentId}`, {
      method: 'DELETE',
    })
  }
  async function sendReminder(scopeType: 'team' | 'organization', scopeId: string, itemId: number) {
    return api(`${base(scopeType, scopeId)}/payment-items/${itemId}/remind`, { method: 'POST' })
  }

  // === Summary ===
  async function getPaymentSummary(scopeType: 'team' | 'organization', scopeId: string) {
    return api<{ data: PaymentSummaryResponse }>(`${base(scopeType, scopeId)}/payment-summary`)
  }

  // === Stripe Checkout ===
  async function createCheckoutSession(itemId: number) {
    return api<{ data: CheckoutSessionResponse }>(`/api/v1/payment-items/${itemId}/checkout`, {
      method: 'POST',
    })
  }

  /**
   * F08.9 P6: 支払い項目を ID で取得する（TERM 型の有効期間表示等に使用）。
   * BE エンドポイント: GET /api/v1/payment-items/{itemId}（P6 実装待ち）
   */
  async function getPaymentItemById(itemId: number) {
    return api<{ data: PaymentItemResponse }>(`/api/v1/payment-items/${itemId}`)
  }

  // === Update Payment ===
  async function updatePayment(
    scopeType: 'team' | 'organization',
    scopeId: string,
    itemId: number,
    paymentId: number,
    body: Record<string, unknown>,
  ) {
    return api(`${base(scopeType, scopeId)}/payment-items/${itemId}/payments/${paymentId}`, {
      method: 'PATCH',
      body,
    })
  }

  // === My Payments ===
  async function getMyPayments() {
    return api<{ data: MyPaymentResponse[] }>('/api/v1/me/payments')
  }

  async function getMySubscriptions() {
    return api<{ data: Record<string, unknown>[] }>('/api/v1/me/subscriptions')
  }

  async function getPaymentRequirements() {
    return api<{ data: Record<string, unknown>[] }>('/api/v1/me/payment-requirements')
  }

  // === Export ===
  async function exportPayments(
    scopeType: 'team' | 'organization',
    scopeId: string,
    itemId: number,
  ) {
    return api(
      `${base(scopeType, scopeId)}/payment-items/${itemId}/payments/export`,
      {
        responseType: 'blob' as const,
      },
    ) as Promise<Blob>
  }

  // === Refund ===
  async function refundPayment(
    scopeType: 'team' | 'organization',
    scopeId: string,
    itemId: number,
    paymentId: number,
  ) {
    return api<{ data: MemberPaymentResponse }>(
      `${base(scopeType, scopeId)}/payment-items/${itemId}/payments/${paymentId}/refund`,
      { method: 'POST' },
    )
  }

  // === Receipt ===
  /**
   * F08.9 P8: 領収書取得。
   * BE: GET /api/v1/member-payments/{memberPaymentId}/receipt
   */
  async function getReceipt(memberPaymentId: number) {
    return api<{ data: MemberPaymentReceiptResponse }>(`/api/v1/member-payments/${memberPaymentId}/receipt`)
  }

  // === Fee Statements ===
  /**
   * F08.9 P8: チーム月次手数料明細を取得する。
   * BE: GET /api/v1/teams/{teamId}/fee-statements?period=YYYY-MM
   */
  async function getFeeStatement(teamId: string, period: string) {
    return api<{ data: FeeStatementResponse }>(`/api/v1/teams/${teamId}/fee-statements`, {
      query: { period },
    })
  }

  // === Subscriptions ===
  async function cancelSubscription(itemId: number, subscriptionId: number) {
    return api(`/api/v1/payment-items/${itemId}/subscriptions/${subscriptionId}`, {
      method: 'DELETE',
    })
  }

  async function resumeSubscription(itemId: number, subscriptionId: number) {
    return api(`/api/v1/payment-items/${itemId}/subscriptions/${subscriptionId}/resume`, {
      method: 'PATCH',
    })
  }

  return {
    getPaymentItems,
    createPaymentItem,
    updatePaymentItem,
    deletePaymentItem,
    getMemberPayments,
    recordManualPayment,
    bulkRecordPayment,
    cancelPayment,
    sendReminder,
    getPaymentSummary,
    createCheckoutSession,
    getPaymentItemById,
    getMyPayments,
    getMySubscriptions,
    getPaymentRequirements,
    updatePayment,
    exportPayments,
    refundPayment,
    cancelSubscription,
    resumeSubscription,
    getReceipt,
    getFeeStatement,
  }
}
