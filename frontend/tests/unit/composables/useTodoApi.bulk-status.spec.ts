// @vitest-environment node
import { beforeEach, describe, expect, it, vi } from 'vitest'

const mockFetch = vi.fn()

vi.mock('~/composables/useApi', () => ({ useApi: () => mockFetch }))

const { useTodoApi } = await import('~/composables/useTodoApi')

describe('useTodoApi.bulkChangeTodoStatus', () => {
  beforeEach(() => mockFetch.mockReset())

  it('TEAM bulk-status PATCH の配列 data とロックスキップIDをそのまま返す', async () => {
    const response = {
      data: [{ id: 10, status: 'COMPLETED' }],
      skippedLockedIds: [11],
    }
    mockFetch.mockResolvedValue(response)

    await expect(
      useTodoApi().bulkChangeTodoStatus('team', 'team-1', [10, 11], 'COMPLETED'),
    ).resolves.toEqual(response)

    expect(mockFetch).toHaveBeenCalledWith('/api/v1/teams/team-1/todos/bulk-status', {
      method: 'PATCH',
      body: { todoIds: [10, 11], status: 'COMPLETED' },
    })
  })

  it('ORG bulk-status PATCH も同じ応答契約を使う', async () => {
    const response = { data: [], skippedLockedIds: [21, 22] }
    mockFetch.mockResolvedValue(response)

    await expect(
      useTodoApi().bulkChangeTodoStatus('organization', 'org-1', [21, 22], 'COMPLETED'),
    ).resolves.toEqual(response)

    expect(mockFetch).toHaveBeenCalledWith('/api/v1/organizations/org-1/todos/bulk-status', {
      method: 'PATCH',
      body: { todoIds: [21, 22], status: 'COMPLETED' },
    })
  })
})
