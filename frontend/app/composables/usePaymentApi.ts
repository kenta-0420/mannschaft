import type { PaymentItemResponse, MemberPaymentResponse, CheckoutSessionResponse, PaymentSummaryResponse, MyPaymentResponse } from '~/types/payment'

export function usePaymentApi() {
  const api = useApi()

  function base(scopeType: 'team' | 'organization', scopeId: number) {
    return scopeType === 'team' ? `/api/v1/teams/${scopeId}` : `/api/v1/organizations/${scopeId}`
  }

  // === Payment Items ===
  async function getPaymentItems(scopeType: 'team' | 'organization', scopeId: number) { return api<{ data: PaymentItemResponse[] }>(`${base(scopeType, scopeId)}/payment-items`) }
  async function createPaymentItem(scopeType: 'team' | 'organization', scopeId: number, body: Record<string, unknown>) { return api<{ data: PaymentItemResponse }>(`${base(scopeType, scopeId)}/payment-items`, { method: 'POST', body }) }
  async function updatePaymentItem(scopeType: 'team' | 'organization', scopeId: number, itemId: number, body: Record<string, unknown>) { return api(`${base(scopeType, scopeId)}/payment-items/${itemId}`, { method: 'PATCH', body }) }
  async function deletePaymentItem(scopeType: 'team' | 'organization', scopeId: number, itemId: number) { return api(`${base(scopeType, scopeId)}/payment-items/${itemId}`, { method: 'DELETE' }) }

  // === Member Payments ===
  async function getMemberPayments(scopeType: 'team' | 'organization', scopeId: number, itemId: number) { return api<{ data: MemberPaymentResponse[] }>(`${base(scopeType, scopeId)}/payment-items/${itemId}/payments`) }
  async function recordManualPayment(scopeType: 'team' | 'organization', scopeId: number, itemId: number, body: Record<string, unknown>) { return api(`${base(scopeType, scopeId)}/payment-items/${itemId}/payments`, { method: 'POST', body }) }
  async function bulkRecordPayment(scopeType: 'team' | 'organization', scopeId: number, itemId: number, payments: Array<Record<string, unknown>>) { return api(`${base(scopeType, scopeId)}/payment-items/${itemId}/payments/bulk`, { method: 'POST', body: { payments } }) }
  async function cancelPayment(scopeType: 'team' | 'organization', scopeId: number, itemId: number, paymentId: number) { return api(`${base(scopeType, scopeId)}/payment-items/${itemId}/payments/${paymentId}`, { method: 'DELETE' }) }
  async function sendReminder(scopeType: 'team' | 'organization', scopeId: number, itemId: number) { return api(`${base(scopeType, scopeId)}/payment-items/${itemId}/remind`, { method: 'POST' }) }

  // === Summary ===
  async function getPaymentSummary(scopeType: 'team' | 'organization', scopeId: number) { return api<{ data: PaymentSummaryResponse }>(`${base(scopeType, scopeId)}/payment-summary`) }

  // === Stripe Checkout ===
  async function createCheckoutSession(scopeType: 'team' | 'organization', scopeId: number, itemId: number) { return api<{ data: CheckoutSessionResponse }>(`${base(scopeType, scopeId)}/payment-items/${itemId}/checkout`, { method: 'POST' }) }

  // === My Payments ===
  async function getMyPayments() { return api<{ data: MyPaymentResponse[] }>('/api/v1/payments/me') }

  return { getPaymentItems, createPaymentItem, updatePaymentItem, deletePaymentItem, getMemberPayments, recordManualPayment, bulkRecordPayment, cancelPayment, sendReminder, getPaymentSummary, createCheckoutSession, getMyPayments }
}
