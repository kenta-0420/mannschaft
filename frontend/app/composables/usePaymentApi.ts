import type {
  PaymentItemResponse,
  MemberPaymentResponse,
  CheckoutSessionResponse,
  PaymentSummaryResponse,
  MyPaymentResponse,
} from '~/types/payment'

export function usePaymentApi() {
  const api = useApi()

  function base(scopeType: 'team' | 'organization', scopeId: number) {
    return scopeType === 'team' ? `/api/v1/teams/${scopeId}` : `/api/v1/organizations/${scopeId}`
  }

  // === Payment Items ===
  async function getPaymentItems(scopeType: 'team' | 'organization', scopeId: number) {
    return api<{ data: PaymentItemResponse[] }>(`${base(scopeType, scopeId)}/payment-items`)
  }
  async function createPaymentItem(
    scopeType: 'team' | 'organization',
    scopeId: number,
    body: Record<string, unknown>,
  ) {
    return api<{ data: PaymentItemResponse }>(`${base(scopeType, scopeId)}/payment-items`, {
      method: 'POST',
      body,
    })
  }
  async function updatePaymentItem(
    scopeType: 'team' | 'organization',
    scopeId: number,
    itemId: number,
    body: Record<string, unknown>,
  ) {
    return api(`${base(scopeType, scopeId)}/payment-items/${itemId}`, { method: 'PATCH', body })
  }
  async function deletePaymentItem(
    scopeType: 'team' | 'organization',
    scopeId: number,
    itemId: number,
  ) {
    return api(`${base(scopeType, scopeId)}/payment-items/${itemId}`, { method: 'DELETE' })
  }

  // === Member Payments ===
  async function getMemberPayments(
    scopeType: 'team' | 'organization',
    scopeId: number,
    itemId: number,
  ) {
    return api<{ data: MemberPaymentResponse[] }>(
      `${base(scopeType, scopeId)}/payment-items/${itemId}/payments`,
    )
  }
  async function recordManualPayment(
    scopeType: 'team' | 'organization',
    scopeId: number,
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
    scopeId: number,
    itemId: number,
    payments: Array<Record<string, unknown>>,
  ) {
    return api(`${base(scopeType, scopeId)}/payment-items/${itemId}/payments/bulk`, {
      method: 'POST',
      body: { payments },
    })
  }
  async function cancelPayment(
    scopeType: 'team' | 'organization',
    scopeId: number,
    itemId: number,
    paymentId: number,
  ) {
    return api(`${base(scopeType, scopeId)}/payment-items/${itemId}/payments/${paymentId}`, {
      method: 'DELETE',
    })
  }
  async function sendReminder(scopeType: 'team' | 'organization', scopeId: number, itemId: number) {
    return api(`${base(scopeType, scopeId)}/payment-items/${itemId}/remind`, { method: 'POST' })
  }

  // === Summary ===
  async function getPaymentSummary(scopeType: 'team' | 'organization', scopeId: number) {
    return api<{ data: PaymentSummaryResponse }>(`${base(scopeType, scopeId)}/payment-summary`)
  }

  // === Stripe Checkout ===
  async function createCheckoutSession(itemId: number) {
    return api<{ data: CheckoutSessionResponse }>(`/api/v1/payment-items/${itemId}/checkout`, {
      method: 'POST',
    })
  }

  // === Update Payment ===
  async function updatePayment(
    scopeType: 'team' | 'organization',
    scopeId: number,
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
    scopeId: number,
    itemId: number,
  ) {
    return api<Blob>(`${base(scopeType, scopeId)}/payment-items/${itemId}/payments/export`)
  }

  // === Refund ===
  async function refundPayment(
    scopeType: 'team' | 'organization',
    scopeId: number,
    itemId: number,
    paymentId: number,
  ) {
    return api<{ data: MemberPaymentResponse }>(
      `${base(scopeType, scopeId)}/payment-items/${itemId}/payments/${paymentId}/refund`,
      { method: 'POST' },
    )
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
    getMyPayments,
    getMySubscriptions,
    getPaymentRequirements,
    updatePayment,
    exportPayments,
    refundPayment,
    cancelSubscription,
    resumeSubscription,
  }
}
