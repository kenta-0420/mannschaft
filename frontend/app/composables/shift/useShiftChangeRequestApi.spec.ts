import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import { useShiftChangeRequestApi } from './useShiftChangeRequestApi'

const mockApi = vi.fn()
mockNuxtImport('useApi', () => () => mockApi)

// 期待値は BE の ShiftChangeRequestController
// （@RequestMapping("/api/v1/shifts/change-requests")）のマッピングを正とする。
describe('useShiftChangeRequestApi', () => {
  beforeEach(() => {
    mockApi.mockReset()
    mockApi.mockResolvedValue({ data: { id: 7 } })
  })

  it('createChangeRequest: POST /api/v1/shifts/change-requests', async () => {
    const payload = { slotId: 1, reason: 'r' } as never
    await useShiftChangeRequestApi().createChangeRequest(payload)
    expect(mockApi).toHaveBeenCalledWith('/api/v1/shifts/change-requests', {
      method: 'POST',
      body: payload,
    })
  })

  it('listChangeRequests: GET /api/v1/shifts/change-requests?scheduleId=', async () => {
    await useShiftChangeRequestApi().listChangeRequests(425)
    expect(mockApi).toHaveBeenCalledWith('/api/v1/shifts/change-requests?scheduleId=425')
  })

  it('getChangeRequest: GET /api/v1/shifts/change-requests/{id}', async () => {
    await useShiftChangeRequestApi().getChangeRequest(7)
    expect(mockApi).toHaveBeenCalledWith('/api/v1/shifts/change-requests/7')
  })

  it('reviewChangeRequest: PATCH /api/v1/shifts/change-requests/{id}/review', async () => {
    const payload = { decision: 'APPROVED' } as never
    await useShiftChangeRequestApi().reviewChangeRequest(7, payload)
    expect(mockApi).toHaveBeenCalledWith('/api/v1/shifts/change-requests/7/review', {
      method: 'PATCH',
      body: payload,
    })
  })

  it('withdrawChangeRequest: DELETE /api/v1/shifts/change-requests/{id}', async () => {
    await useShiftChangeRequestApi().withdrawChangeRequest(7)
    expect(mockApi).toHaveBeenCalledWith('/api/v1/shifts/change-requests/7', {
      method: 'DELETE',
    })
  })
})
