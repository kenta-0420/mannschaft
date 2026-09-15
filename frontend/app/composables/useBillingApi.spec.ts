import { describe, expect, it, vi, beforeEach } from 'vitest'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import { useBillingApi } from './useBillingApi'

/**
 * Billing Center PR6a: 解約予約・撤回の新エンドポイント呼び出しを検証する
 * （Codex 検分 P1 是正。BillingManagePanel からの実結線は BillingManagePanel.spec.ts 側で検証する）。
 */

const mockApi = vi.fn()
mockNuxtImport('useApi', () => () => mockApi)

beforeEach(() => {
  mockApi.mockReset()
})

describe('useBillingApi — 解約予約・撤回（AC-51〜57）', () => {
  it('cancelContractReservation は POST …/cancel を Idempotency-Key 付き・{version} 本文で呼ぶ', async () => {
    mockApi.mockResolvedValueOnce({ data: { contractId: 'c1', status: 'SCHEDULED', version: 1 } })

    const api = useBillingApi()
    await api.cancelContractReservation('c1', 0)

    expect(mockApi).toHaveBeenCalledTimes(1)
    const [url, options] = mockApi.mock.calls[0] as [string, Record<string, unknown>]
    expect(url).toBe('/api/v1/me/billing/contracts/c1/cancel')
    expect(options.method).toBe('POST')
    expect(options.body).toEqual({ version: 0 })
    expect((options.headers as Record<string, string>)['Idempotency-Key']).toBeTruthy()
  })

  it('resumeContractCancellation は DELETE …/cancel を Idempotency-Key 付き・{version} 本文で呼ぶ', async () => {
    mockApi.mockResolvedValueOnce({ data: { contractId: 'c1', status: 'ACTIVE', version: 2 } })

    const api = useBillingApi()
    await api.resumeContractCancellation('c1', 1)

    expect(mockApi).toHaveBeenCalledTimes(1)
    const [url, options] = mockApi.mock.calls[0] as [string, Record<string, unknown>]
    expect(url).toBe('/api/v1/me/billing/contracts/c1/cancel')
    expect(options.method).toBe('DELETE')
    expect(options.body).toEqual({ version: 1 })
    expect((options.headers as Record<string, string>)['Idempotency-Key']).toBeTruthy()
  })

  it('連続呼び出しごとに異なる Idempotency-Key を発行する（連打の二重発行防止）', async () => {
    mockApi.mockResolvedValue({ data: {} })
    const api = useBillingApi()

    await api.cancelContractReservation('c1', 0)
    await api.cancelContractReservation('c1', 0)

    const key1 = (mockApi.mock.calls[0]?.[1] as { headers: Record<string, string> }).headers['Idempotency-Key']
    const key2 = (mockApi.mock.calls[1]?.[1] as { headers: Record<string, string> }).headers['Idempotency-Key']
    expect(key1).not.toBe(key2)
  })
})
