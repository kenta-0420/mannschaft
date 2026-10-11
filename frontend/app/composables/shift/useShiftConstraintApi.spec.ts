import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import { useShiftConstraintApi } from './useShiftConstraintApi'

const mockApi = vi.fn()
mockNuxtImport('useApi', () => () => mockApi)

// 期待値は BE の MemberWorkConstraintController
// （@RequestMapping("/api/v1/shifts/teams/{teamId}/work-constraints")）のマッピングを正とする。
describe('useShiftConstraintApi', () => {
  const BASE = '/api/v1/shifts/teams/10/work-constraints'
  const body = { maxMonthlyDays: 20, maxConsecutiveDays: 5 }

  beforeEach(() => {
    mockApi.mockReset()
    mockApi.mockResolvedValue({ data: [] })
  })

  it('getWorkConstraints: GET .../work-constraints', async () => {
    await useShiftConstraintApi().getWorkConstraints('10')
    expect(mockApi).toHaveBeenCalledWith(BASE)
  })

  it('upsertDefaultConstraint: PUT .../work-constraints/default', async () => {
    await useShiftConstraintApi().upsertDefaultConstraint('10', body)
    expect(mockApi).toHaveBeenCalledWith(`${BASE}/default`, { method: 'PUT', body })
  })

  it('upsertMemberConstraint: PUT .../work-constraints/members/{userId}', async () => {
    await useShiftConstraintApi().upsertMemberConstraint('10', 23, body)
    expect(mockApi).toHaveBeenCalledWith(`${BASE}/members/23`, { method: 'PUT', body })
  })

  it('deleteMemberConstraint: DELETE .../work-constraints/members/{userId}', async () => {
    await useShiftConstraintApi().deleteMemberConstraint('10', 23)
    expect(mockApi).toHaveBeenCalledWith(`${BASE}/members/23`, { method: 'DELETE' })
  })
})
