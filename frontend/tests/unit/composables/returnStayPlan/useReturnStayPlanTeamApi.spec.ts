import { beforeEach, describe, expect, it, vi } from 'vitest'
const mockFetch = vi.fn()
vi.mock('~/composables/useApi', () => ({ useApi: () => mockFetch }))
const { chunkMemberIds, mergeMemberPlans, useReturnStayPlanTeamApi } = await import('~/composables/returnStayPlan/useReturnStayPlanTeamApi')
describe('useReturnStayPlanTeamApi', () => {
  beforeEach(() => mockFetch.mockReset())
  it('400件単位でmemberIdsを繰り返しクエリにして分割取得する', async () => {
    const ids = Array.from({ length: 401 }, (_, index) => index + 1)
    mockFetch.mockResolvedValue({ data: { items: [] } })
    await useReturnStayPlanTeamApi().fetchForMembers('class/a', ids)
    expect(mockFetch).toHaveBeenCalledTimes(2)
    const firstUrl = mockFetch.mock.calls[0]![0] as string
    const secondUrl = mockFetch.mock.calls[1]![0] as string
    expect(firstUrl).toContain('/teams/class%2Fa/members/return-stay-plans?')
    expect(firstUrl.match(/memberIds=/g)).toHaveLength(400)
    expect(secondUrl.match(/memberIds=/g)).toHaveLength(1)
  })
  it('欠落メンバーを空配列で補完し、複数バッチをmemberIdで統合する', () => {
    expect(chunkMemberIds([1, 2, 3], 2)).toEqual([[1, 2], [3]])
    expect(mergeMemberPlans([1, 2, 3], [
      { memberId: 2, plans: [{ id: 'p2' }] as never[] },
      { memberId: 1, plans: [{ id: 'p1' }] as never[] },
    ])).toEqual([{ memberId: 1, plans: [{ id: 'p1' }] }, { memberId: 2, plans: [{ id: 'p2' }] }, { memberId: 3, plans: [] }])
  })
  it('一つのチャンクが失敗した場合は全体をrejectして部分結果を返さない', async () => {
    mockFetch.mockResolvedValueOnce({ data: { items: [{ memberId: 1, plans: [{ id: 'p1' }] }] } })
    mockFetch.mockRejectedValueOnce(new Error('network'))
    await expect(useReturnStayPlanTeamApi().fetchForMembers('team-1', Array.from({ length: 401 }, (_, i) => i + 1))).rejects.toThrow('network')
  })
  it('新しい取得を開始すると、古い取得をAbortしてnullを返す', async () => {
    mockFetch.mockImplementationOnce((_url: string, options: { signal: AbortSignal }) => new Promise((_resolve, reject) => options.signal.addEventListener('abort', () => reject(new DOMException('aborted', 'AbortError')))))
    mockFetch.mockResolvedValueOnce({ data: { items: [] } })
    const api = useReturnStayPlanTeamApi()
    const oldRequest = api.fetchForMembers('team-1', [1])
    const newRequest = api.fetchForMembers('team-1', [2])
    await expect(oldRequest).resolves.toBeNull()
    await expect(newRequest).resolves.toEqual([{ memberId: 2, plans: [] }])
  })
})
