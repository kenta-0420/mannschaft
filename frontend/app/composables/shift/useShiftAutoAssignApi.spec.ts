import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import { useShiftAutoAssignApi } from './useShiftAutoAssignApi'

const mockApi = vi.fn()
mockNuxtImport('useApi', () => () => mockApi)

// 期待値は BE の ShiftAutoAssignController を正とする。
// 破棄 API は DELETE /api/v1/shifts/schedules/{scheduleId}/auto-assign で、
// 必須の @RequestBody Long runId（JSON の数値）を要求する。
describe('useShiftAutoAssignApi.revokeAutoAssign', () => {
  beforeEach(() => {
    mockApi.mockReset()
    mockApi.mockResolvedValue(undefined)
  })

  it('runId を JSON 数値のボディで DELETE する', async () => {
    await useShiftAutoAssignApi().revokeAutoAssign(425, 77)

    expect(mockApi).toHaveBeenCalledWith('/api/v1/shifts/schedules/425/auto-assign', {
      method: 'DELETE',
      headers: { 'Content-Type': 'application/json' },
      body: '77',
    })
  })
})

describe('useShiftAutoAssignApi assignment-runs', () => {
  beforeEach(() => {
    mockApi.mockReset()
    mockApi.mockResolvedValue({ data: {} })
  })

  it('getAssignmentRunDetail: GET /api/v1/shifts/assignment-runs/{runId}', async () => {
    await useShiftAutoAssignApi().getAssignmentRunDetail(77)
    expect(mockApi).toHaveBeenCalledWith('/api/v1/shifts/assignment-runs/77')
  })
})
