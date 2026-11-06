import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import { useEmergencyClosureApi } from '~/composables/useEmergencyClosureApi'

/**
 * useEmergencyClosureApi.sendClosure の Idempotency-Key ヘッダ付与（CMP-261104-0334 / P1）。
 */
const mockApi = vi.fn()
mockNuxtImport('useApi', () => () => mockApi)

const body = {
  startDate: '2026-11-05',
  endDate: '2026-11-05',
  startTime: null,
  endTime: null,
  reason: '設備点検',
  subject: '臨時休業',
  messageBody: '本文',
  cancelReservations: false,
}

beforeEach(() => {
  mockApi.mockReset()
  mockApi.mockResolvedValue({ data: { sentCount: 1 } })
})

describe('sendClosure の Idempotency-Key', () => {
  it('0334-1: 渡したキーが Idempotency-Key ヘッダとして POST に付く', async () => {
    await useEmergencyClosureApi().sendClosure('team-a', body, 'key-123')
    const opts = mockApi.mock.calls[0]![1] as { method: string, headers: Record<string, string> }
    expect(opts.method).toBe('POST')
    expect(opts.headers['Idempotency-Key']).toBe('key-123')
  })
})
