import { describe, expect, it, vi } from 'vitest'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import { useShiftApi } from './useShiftApi'

const mockApi = vi.fn()
mockNuxtImport('useApi', () => () => mockApi)

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
