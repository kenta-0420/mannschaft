import { describe, it, expect, beforeEach, vi } from 'vitest'

/**
 * F22.1 市の謝礼決済 useMarketPaymentApi ユニットテスト。
 *
 * 検証観点（BE 実在 EP・camelCase 1:1）:
 *   MKT-PAY-001: createOnboardingLink → POST /api/v1/payment/connect/onboarding-link
 *   MKT-PAY-002: getConnectStatus → GET /api/v1/payment/connect/status（USER は scopeId 省略）
 *   MKT-PAY-003: getConnectStatus → scopeId 指定時は query に載せる
 *   MKT-PAY-004: refund → POST /api/v1/payment/escrow/{id}/refund に camelCase body
 *   MKT-PAY-005: getRecruitmentPaymentIntent → GET .../recruitment/{listingId}/{participantId}/payment-intent
 *   MKT-PAY-006: getEscrow → GET /api/v1/payment/escrow/{id}
 */

const mockFetch = vi.fn()

vi.mock('~/composables/useApi', () => ({
  useApi: () => mockFetch,
}))

const { useMarketPaymentApi } = await import('~/composables/useMarketPaymentApi')

describe('useMarketPaymentApi', () => {
  beforeEach(() => {
    mockFetch.mockReset()
  })

  it('MKT-PAY-001: createOnboardingLink は POST /api/v1/payment/connect/onboarding-link に body を渡す', async () => {
    mockFetch.mockResolvedValueOnce({
      data: {
        connectAccountId: 'ca-1',
        stripeAccountId: 'acct_1',
        onboardingStatus: 'ONBOARDING',
        onboardingUrl: 'https://connect.stripe.com/setup/x',
        expiresAt: '2026-06-10T00:00:00',
      },
    })
    const api = useMarketPaymentApi()
    const body = {
      scopeKind: 'TEAM' as const,
      scopeId: 42,
      returnUrl: 'https://app/return',
      refreshUrl: 'https://app/refresh',
    }
    await api.createOnboardingLink(body)

    expect(mockFetch).toHaveBeenCalledWith('/api/v1/payment/connect/onboarding-link', {
      method: 'POST',
      body,
    })
  })

  it('MKT-PAY-002: getConnectStatus は USER 時 scopeId を query に含めない', async () => {
    mockFetch.mockResolvedValueOnce({
      data: {
        connectAccountId: 'ca-1',
        scopeKind: 'USER',
        scopeId: null,
        onboardingStatus: 'READY',
        chargesEnabled: true,
        payoutsEnabled: true,
        requirementsDue: [],
      },
    })
    const api = useMarketPaymentApi()
    await api.getConnectStatus('USER')

    expect(mockFetch).toHaveBeenCalledWith('/api/v1/payment/connect/status', {
      query: { scopeKind: 'USER' },
    })
  })

  it('MKT-PAY-003: getConnectStatus は scopeId 指定時に query へ載せる', async () => {
    mockFetch.mockResolvedValueOnce({ data: {} })
    const api = useMarketPaymentApi()
    await api.getConnectStatus('ORG', 7)

    expect(mockFetch).toHaveBeenCalledWith('/api/v1/payment/connect/status', {
      query: { scopeKind: 'ORG', scopeId: 7 },
    })
  })

  it('MKT-PAY-004: refund は POST /api/v1/payment/escrow/{id}/refund に camelCase body を渡す', async () => {
    mockFetch.mockResolvedValueOnce({
      data: {
        escrowId: 'e-1',
        status: 'PARTIALLY_REFUNDED',
        refundedAmount: 5000,
        residualAmount: 5000,
      },
    })
    const api = useMarketPaymentApi()
    const body = {
      amount: 5000,
      feeBearer: 'PAYEE' as const,
      reason: 'cancellation',
      reasonDetail: '中止',
    }
    await api.refund('e-1', body)

    expect(mockFetch).toHaveBeenCalledWith('/api/v1/payment/escrow/e-1/refund', {
      method: 'POST',
      body,
    })
  })

  it('MKT-PAY-005: getRecruitmentPaymentIntent は GET recruitment/{listingId}/{participantId}/payment-intent を呼ぶ', async () => {
    mockFetch.mockResolvedValueOnce({
      data: {
        clientSecret: 'pi_123_secret_abc',
        escrowTransactionId: '0190-uuid',
        status: 'PENDING_CONFIRMATION',
        faceAmount: 10000,
        chargeAmount: 10250,
        applicationFeeAmount: 250,
      },
    })
    const api = useMarketPaymentApi()
    await api.getRecruitmentPaymentIntent(11, 22)

    expect(mockFetch).toHaveBeenCalledWith(
      '/api/v1/payment/escrow/recruitment/11/22/payment-intent',
    )
  })

  it('MKT-PAY-006: getEscrow は GET /api/v1/payment/escrow/{id} を呼ぶ', async () => {
    mockFetch.mockResolvedValueOnce({
      data: {
        clientSecret: null,
        escrowTransactionId: '0190-uuid',
        status: 'AUTHORIZED',
        faceAmount: 10000,
        chargeAmount: 10250,
        applicationFeeAmount: 250,
      },
    })
    const api = useMarketPaymentApi()
    await api.getEscrow('0190-uuid')

    expect(mockFetch).toHaveBeenCalledWith('/api/v1/payment/escrow/0190-uuid')
  })
})
