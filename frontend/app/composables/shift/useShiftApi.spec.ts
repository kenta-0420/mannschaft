import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import { useRuntimeConfig } from '#app'
import { useShiftApi } from './useShiftApi'
import { useShiftUtilApi } from './useShiftUtilApi'

const mockApi = vi.fn()
mockNuxtImport('useApi', () => () => mockApi)
mockNuxtImport('useAuthStore', () => () => ({ accessToken: null }))

describe('useShiftApi.remindUnsubmitted', () => {
  it('schedule IDを手動督促APIへPOSTし、dataを返す', async () => {
    const result = {
      scheduleId: 399,
      remindedCount: 2,
      remindedUserIds: [23, 24],
    }
    mockApi.mockResolvedValue({ data: result })

    const response = await useShiftApi().remindUnsubmitted(399)

    expect(mockApi).toHaveBeenCalledWith('/api/v1/shifts/schedules/399/remind', {
      method: 'POST',
    })
    expect(response).toEqual(result)
  })
})

describe('useShiftUtilApi.downloadShiftPdf', () => {
  let previousApiBase: string

  beforeEach(() => {
    const config = useRuntimeConfig()
    previousApiBase = config.public.apiBase
    config.public.apiBase = 'http://localhost:8080'
  })

  afterEach(() => {
    useRuntimeConfig().public.apiBase = previousApiBase
    vi.unstubAllGlobals()
  })

  it.each(['team', 'personal'] as const)('%s PDFはhostにAPI prefixを1回だけ付ける', async (layout) => {
    const blob = new Blob(['%PDF-1.7'], { type: 'application/pdf' })
    const fetch = vi.fn().mockResolvedValue(blob)
    vi.stubGlobal('$fetch', fetch)

    const result = await useShiftUtilApi().downloadShiftPdf(399, layout)

    expect(fetch).toHaveBeenCalledWith(
      `http://localhost:8080/api/v1/shifts/schedules/399/pdf?layout=${layout}`,
      { responseType: 'blob', credentials: 'include', headers: {} },
    )
    expect(result).toBe(blob)
  })
})
