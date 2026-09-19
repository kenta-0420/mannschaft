import type {
  CreatePaymentRequestRequest,
  PaymentRequestListResponse,
  PaymentRequestPageResponse,
  PaymentRequestPayResponse,
  PaymentRequestResponse,
  PaymentRequestStatus,
  TeamPaymentAdvanceResponse,
} from '~/types/paymentRequest'

/**
 * P7 支払依頼 API。
 *
 * 支払い開始の Idempotency-Key はブラウザのメモリだけで保持する。client secret と異なり
 * Stripe に渡す値ではなく、storage に永続化もしない。同じ支払依頼への通信不明時の再試行は
 * 同じキーを再利用し、サーバー側の同一 PaymentIntent へ収束させる。
 */
const paymentIdempotencyKeys = new Map<string, string>()
const paymentIdempotencyStoragePrefix = 'mannschaft:payment-request-idempotency:'

function paymentRequestKey(teamId: string | number, paymentRequestId: string): string {
  return `${teamId}:${paymentRequestId}`
}

function paymentRequestStorageKey(teamId: string | number, paymentRequestId: string): string {
  return `${paymentIdempotencyStoragePrefix}${paymentRequestKey(teamId, paymentRequestId)}`
}

function getSessionStorage(): Storage | null {
  if (!import.meta.client) return null
  try {
    return window.sessionStorage
  } catch {
    // ブラウザ設定で sessionStorage が拒否されても、同一画面内のメモリ保持で再試行を継続できる。
    // eslint-disable-next-line no-restricted-syntax
    return null
  }
}

export function usePaymentRequestApi() {
  const api = useApi()

  function getPaymentRequestIdempotencyKey(teamId: string | number, paymentRequestId: string): string {
    const key = paymentRequestKey(teamId, paymentRequestId)
    const existing = paymentIdempotencyKeys.get(key)
    if (existing) {
      return existing
    }
    const restored = getSessionStorage()?.getItem(paymentRequestStorageKey(teamId, paymentRequestId))
    if (restored) {
      paymentIdempotencyKeys.set(key, restored)
      return restored
    }
    const created = crypto.randomUUID()
    paymentIdempotencyKeys.set(key, created)
    getSessionStorage()?.setItem(paymentRequestStorageKey(teamId, paymentRequestId), created)
    return created
  }

  function clearPaymentRequestIdempotencyKey(teamId: string | number, paymentRequestId: string) {
    paymentIdempotencyKeys.delete(paymentRequestKey(teamId, paymentRequestId))
    getSessionStorage()?.removeItem(paymentRequestStorageKey(teamId, paymentRequestId))
  }

  async function createPaymentRequest(orgId: string | number, body: CreatePaymentRequestRequest) {
    return api<{ data: PaymentRequestResponse }>(`/api/v1/organizations/${orgId}/payment-requests`, {
      method: 'POST',
      body,
    })
  }

  async function listOrganizationPaymentRequests(
    orgId: string | number,
    params?: { status?: PaymentRequestStatus; page?: number; size?: number },
  ) {
    const query = new URLSearchParams()
    if (params?.status) query.set('status', params.status)
    query.set('page', String(params?.page ?? 0))
    query.set('size', String(params?.size ?? 20))
    return api<PaymentRequestPageResponse>(`/api/v1/organizations/${orgId}/payment-requests?${query}`)
  }

  async function sendPaymentRequest(orgId: string | number, paymentRequestId: string) {
    return api<{ data: PaymentRequestResponse }>(
      `/api/v1/organizations/${orgId}/payment-requests/${paymentRequestId}/send`,
      { method: 'PATCH' },
    )
  }

  async function cancelPaymentRequest(orgId: string | number, paymentRequestId: string) {
    return api<{ data: PaymentRequestResponse }>(
      `/api/v1/organizations/${orgId}/payment-requests/${paymentRequestId}/cancel`,
      { method: 'PATCH' },
    )
  }

  async function listTeamPaymentRequests(teamId: string | number) {
    return api<PaymentRequestListResponse>(`/api/v1/teams/${teamId}/payment-requests`)
  }

  async function getTeamPaymentRequest(teamId: string | number, paymentRequestId: string) {
    return api<{ data: PaymentRequestResponse }>(`/api/v1/teams/${teamId}/payment-requests/${paymentRequestId}`)
  }

  async function payPaymentRequest(
    teamId: string | number,
    paymentRequestId: string,
    idempotencyKey = getPaymentRequestIdempotencyKey(teamId, paymentRequestId),
  ) {
    return api<{ data: PaymentRequestPayResponse }>(
      `/api/v1/teams/${teamId}/payment-requests/${paymentRequestId}/pay`,
      {
        method: 'POST',
        headers: { 'Idempotency-Key': idempotencyKey },
      },
    )
  }

  async function listTeamPaymentAdvances(teamId: string | number) {
    return api<{ data: TeamPaymentAdvanceResponse[] }>(`/api/v1/teams/${teamId}/payment-advances`)
  }

  async function confirmPaymentAdvanceSettlement(teamId: string | number, advanceId: string) {
    return api<{ data: TeamPaymentAdvanceResponse }>(
      `/api/v1/teams/${teamId}/payment-advances/${advanceId}/confirm-settlement`,
      { method: 'POST' },
    )
  }

  return {
    getPaymentRequestIdempotencyKey,
    clearPaymentRequestIdempotencyKey,
    createPaymentRequest,
    listOrganizationPaymentRequests,
    sendPaymentRequest,
    cancelPaymentRequest,
    listTeamPaymentRequests,
    getTeamPaymentRequest,
    payPaymentRequest,
    listTeamPaymentAdvances,
    confirmPaymentAdvanceSettlement,
  }
}
