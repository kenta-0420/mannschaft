import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import { usePaymentRequestApi } from './usePaymentRequestApi'

const mockApi = vi.fn()
mockNuxtImport('useApi', () => () => mockApi)

beforeEach(() => {
  mockApi.mockReset()
  sessionStorage.clear()
})

describe('usePaymentRequestApi', () => {
  it('支払い開始は Idempotency-Key を付ける', async () => {
    mockApi.mockResolvedValue({ data: { clientSecret: 'pi_secret' } })

    await usePaymentRequestApi().payPaymentRequest(10, 'request-1')

    const [url, options] = mockApi.mock.calls[0] as [string, { method: string; headers: Record<string, string> }]
    expect(url).toBe('/api/v1/teams/10/payment-requests/request-1/pay')
    expect(options.method).toBe('POST')
    expect(options.headers['Idempotency-Key']).toBeTruthy()
  })

  it('通信結果不明の再試行では同じ支払依頼の Idempotency-Key を再利用する', async () => {
    mockApi.mockRejectedValueOnce(new Error('network failed')).mockResolvedValueOnce({ data: {} })
    const paymentApi = usePaymentRequestApi()

    await expect(paymentApi.payPaymentRequest(10, 'request-1')).rejects.toThrow('network failed')
    await paymentApi.payPaymentRequest(10, 'request-1')

    const first = (mockApi.mock.calls[0]?.[1] as { headers: Record<string, string> }).headers['Idempotency-Key']
    const retry = (mockApi.mock.calls[1]?.[1] as { headers: Record<string, string> }).headers['Idempotency-Key']
    expect(retry).toBe(first)
  })

  it('呼び出し元が保持した Idempotency-Key を明示して再試行できる', async () => {
    mockApi.mockResolvedValue({ data: {} })
    const paymentApi = usePaymentRequestApi()
    const idempotencyKey = paymentApi.getPaymentRequestIdempotencyKey(10, 'request-2')

    await paymentApi.payPaymentRequest(10, 'request-2', idempotencyKey)

    const options = mockApi.mock.calls[0]?.[1] as { headers: Record<string, string> }
    expect(options.headers['Idempotency-Key']).toBe(idempotencyKey)
  })

  it('reload 後は sessionStorage の同一 Idempotency-Key を復元する', () => {
    sessionStorage.setItem('mannschaft:payment-request-idempotency:10:request-restored', 'retry-key')

    const key = usePaymentRequestApi().getPaymentRequestIdempotencyKey(10, 'request-restored')

    expect(key).toBe('retry-key')
  })

  it('Idempotency-Key の消去は sessionStorage からも削除する', () => {
    const paymentApi = usePaymentRequestApi()
    const key = paymentApi.getPaymentRequestIdempotencyKey(10, 'request-clear')
    expect(sessionStorage.getItem('mannschaft:payment-request-idempotency:10:request-clear')).toBe(key)

    paymentApi.clearPaymentRequestIdempotencyKey(10, 'request-clear')

    expect(sessionStorage.getItem('mannschaft:payment-request-idempotency:10:request-clear')).toBeNull()
  })
})
