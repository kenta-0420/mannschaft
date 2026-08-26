import { describe, it, expect, beforeEach, vi } from 'vitest'

/**
 * F08.9 P5 useMembershipSubscriptionApi ユニットテスト。
 *
 * <p>継続課金 API ラッパーの URL / HTTP メソッド / レスポンス透過を検証する。
 * BE は ApiResponse（{ data: ... }）でラップするため、各メソッドは透過してそのまま返す。</p>
 *
 * モック方針:
 *  - `useApi` を vi.mock でスタブ化し、mockFetch（関数）を差し込む。
 *
 * テストケース一覧:
 *  MSUB-API-001: listMySubscriptions — GET /api/v1/me/membership-subscriptions、data 透過
 *  MSUB-API-002: cancelSubscription — DELETE /api/v1/membership-subscriptions/{id}
 *  MSUB-API-003: skipSubscription — POST …/{id}/skip
 *  MSUB-API-004: resumeSubscription — POST …/{id}/resume
 *  MSUB-API-005: subscribe — POST /api/v1/payment-items/{itemId}/subscribe、body 透過
 *  MSUB-API-006: createSetupIntent — POST /api/v1/me/payment-methods/setup-intent
 *  MSUB-API-007: confirmPaymentMethod — POST /api/v1/me/payment-methods/confirm、body 透過
 */

const mockFetch = vi.fn()

vi.mock('~/composables/useApi', () => ({
  useApi: () => mockFetch,
}))

const { useMembershipSubscriptionApi } = await import('~/composables/useMembershipSubscriptionApi')

const SUB_ID = '0190b3e1-0000-7000-8000-000000000001'

describe('useMembershipSubscriptionApi', () => {
  beforeEach(() => {
    mockFetch.mockReset()
  })

  it('MSUB-API-001: listMySubscriptions — GET 一覧 URL、data 透過', async () => {
    const list = [{ id: SUB_ID, status: 'ACTIVE' }]
    mockFetch.mockResolvedValueOnce({ data: list })
    const api = useMembershipSubscriptionApi()

    const res = await api.listMySubscriptions()

    expect(mockFetch).toHaveBeenCalledWith('/api/v1/me/membership-subscriptions')
    expect(res.data).toEqual(list)
  })

  it('MSUB-API-002: cancelSubscription — DELETE {id}', async () => {
    mockFetch.mockResolvedValueOnce({ data: { id: SUB_ID, cancelAtPeriodEnd: true } })
    const api = useMembershipSubscriptionApi()

    const res = await api.cancelSubscription(SUB_ID)

    expect(mockFetch).toHaveBeenCalledWith(`/api/v1/membership-subscriptions/${SUB_ID}`, {
      method: 'DELETE',
    })
    expect(res.data.cancelAtPeriodEnd).toBe(true)
  })

  it('MSUB-API-003: skipSubscription — POST {id}/skip', async () => {
    mockFetch.mockResolvedValueOnce({ data: { id: SUB_ID, skipUntil: '2026-08-01' } })
    const api = useMembershipSubscriptionApi()

    const res = await api.skipSubscription(SUB_ID)

    expect(mockFetch).toHaveBeenCalledWith(`/api/v1/membership-subscriptions/${SUB_ID}/skip`, {
      method: 'POST',
    })
    expect(res.data.skipUntil).toBe('2026-08-01')
  })

  it('MSUB-API-004: resumeSubscription — POST {id}/resume', async () => {
    mockFetch.mockResolvedValueOnce({ data: { id: SUB_ID, skipUntil: null } })
    const api = useMembershipSubscriptionApi()

    const res = await api.resumeSubscription(SUB_ID)

    expect(mockFetch).toHaveBeenCalledWith(`/api/v1/membership-subscriptions/${SUB_ID}/resume`, {
      method: 'POST',
    })
    expect(res.data.skipUntil).toBeNull()
  })

  it('MSUB-API-005: subscribe — POST payment-items/{itemId}/subscribe、body 透過', async () => {
    mockFetch.mockResolvedValueOnce({ data: { id: SUB_ID, status: 'PENDING' } })
    const api = useMembershipSubscriptionApi()
    const body = { beneficiaryUserId: 42, billingAnchorDay: 15 }

    const res = await api.subscribe(7, body)

    expect(mockFetch).toHaveBeenCalledWith('/api/v1/payment-items/7/subscribe', {
      method: 'POST',
      body,
    })
    expect(res.data.status).toBe('PENDING')
  })

  it('MSUB-API-006: createSetupIntent — POST setup-intent', async () => {
    mockFetch.mockResolvedValueOnce({
      data: { setupIntentId: 'seti_1', clientSecret: 'cs_1', status: 'requires_payment_method' },
    })
    const api = useMembershipSubscriptionApi()

    const res = await api.createSetupIntent()

    expect(mockFetch).toHaveBeenCalledWith('/api/v1/me/payment-methods/setup-intent', {
      method: 'POST',
    })
    expect(res.data.setupIntentId).toBe('seti_1')
  })

  it('MSUB-API-007: confirmPaymentMethod — POST confirm、body 透過', async () => {
    mockFetch.mockResolvedValueOnce({ data: { defaultPaymentMethod: 'pm_1', saved: true } })
    const api = useMembershipSubscriptionApi()

    const res = await api.confirmPaymentMethod({ paymentMethodId: 'pm_1' })

    expect(mockFetch).toHaveBeenCalledWith('/api/v1/me/payment-methods/confirm', {
      method: 'POST',
      body: { paymentMethodId: 'pm_1' },
    })
    expect(res.data.saved).toBe(true)
  })
})
