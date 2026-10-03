import { describe, it, expect, beforeEach, vi } from 'vitest'

/**
 * useActivityApi.createActivity ユニットテスト
 *
 * 検証観点:
 *   ACT-API-001: createActivity は scope_type / scope_id をクエリで送り、body を POST する
 *   ACT-API-002: scope_id が数値クエリとして付与される（slug ではなく数値 DB id）
 */

const mockFetch = vi.fn()

vi.mock('~/composables/useApi', () => ({
  useApi: () => mockFetch,
}))

// eslint-disable-next-line import/first
import { useActivityApi } from '~/composables/useActivityApi'

describe('useActivityApi.createActivity', () => {
  beforeEach(() => {
    mockFetch.mockReset()
  })

  it('ACT-API-001: scope_type/scope_id をクエリで送り body を POST する', async () => {
    mockFetch.mockResolvedValueOnce({ data: { id: 1 } })
    const api = useActivityApi()
    const body = {
      templateId: 10,
      title: '練習',
      activityDate: '2026-07-01',
      visibility: 'MEMBERS_ONLY' as const,
      fieldValues: { score: 3 },
    }

    await api.createActivity('TEAM', 42, body)

    expect(mockFetch).toHaveBeenCalledTimes(1)
    const [url, opts] = mockFetch.mock.calls[0]!
    expect(url).toBe('/api/v1/activities?scope_type=TEAM&scope_id=42')
    expect(opts).toEqual({ method: 'POST', body })
  })

  it('ACT-API-002: 組織スコープでも数値 scope_id をクエリに付与する', async () => {
    mockFetch.mockResolvedValueOnce({ data: { id: 2 } })
    const api = useActivityApi()

    await api.createActivity('ORGANIZATION', 7, {
      templateId: 1,
      title: 'イベント',
      activityDate: '2026-07-02',
    })

    const [url] = mockFetch.mock.calls[0]!
    expect(url).toBe('/api/v1/activities?scope_type=ORGANIZATION&scope_id=7')
  })
})
