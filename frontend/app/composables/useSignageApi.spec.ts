import { describe, expect, it, vi } from 'vitest'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import { useSignageApi } from './useSignageApi'

const api = vi.fn()
mockNuxtImport('useApi', () => () => api)

describe('useSignageApi トークン発行契約', () => {
  it.each(['画', '画'.repeat(100)])('選択画面名を必須リクエスト本文として送る', async (name) => {
    api.mockClear()
    api.mockResolvedValue({ data: { id: 1 } })

    await useSignageApi().issueToken(42, name)

    expect(api).toHaveBeenCalledExactlyOnceWith('/api/v1/signage/screens/42/tokens', {
      method: 'POST',
      body: { name, allowedIps: null, expiredAt: null },
    })
  })
})
