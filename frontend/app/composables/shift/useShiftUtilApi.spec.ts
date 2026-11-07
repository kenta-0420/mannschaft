import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import { useShiftUtilApi } from './useShiftUtilApi'

const mockApi = vi.fn()
mockNuxtImport('useApi', () => () => mockApi)

// 期待値は BE の ShiftPdfController
// （@RequestMapping("/api/v1/shifts/schedules/{scheduleId}/pdf")）を正とする。
describe('useShiftUtilApi.downloadShiftPdf', () => {
  beforeEach(() => {
    mockApi.mockReset()
  })

  it.each(['team', 'personal'] as const)(
    'layout=%s: /api/v1 を二重にせず cookie 認証の useApi で blob 取得する',
    async (layout) => {
      const blob = new Blob(['%PDF'], { type: 'application/pdf' })
      mockApi.mockResolvedValue(blob)

      const result = await useShiftUtilApi().downloadShiftPdf(399, layout)

      expect(mockApi).toHaveBeenCalledWith(`/api/v1/shifts/schedules/399/pdf?layout=${layout}`, {
        responseType: 'blob',
      })
      expect(result).toBe(blob)
    },
  )
})
