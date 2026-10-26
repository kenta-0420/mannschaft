import { describe, it, expect, vi } from 'vitest'

/**
 * usePersonalAdminActionRequired ユニットテスト（司令塔第二弾「承認待ち横断集約」）。
 *
 * GET /api/v1/dashboard/admin-action-required の snake_case レスポンスを
 * camelCase に正しく変換することを検証する（AC-B1-6: 各項目の必須フィールド）。
 */

const mockApi = vi.fn()
vi.mock('~/composables/useApi', () => ({
  useApi: () => mockApi,
}))

const { usePersonalAdminActionRequired } = await import('~/composables/usePersonalAdminActionRequired')

describe('usePersonalAdminActionRequired', () => {
  it('AC-B1-6: snake_case レスポンスを camelCase の PersonalAdminActionItem に正しく変換する', async () => {
    mockApi.mockResolvedValueOnce({
      data: {
        items: [
          {
            domain: 'RESERVATION',
            scope_type: 'TEAM',
            scope_id: 10,
            scope_slug: 'team-alpha',
            scope_name: 'チームA',
            item_id: '501',
            title: '予約: コート利用申請',
            requested_by: '山田太郎',
            requested_at: '2026-07-10T09:00:00',
            detail_route: '/teams/team-alpha/admin/reservations/501',
          },
        ],
        total_pending: 3,
      },
    })

    const { fetchAdminActionRequired } = usePersonalAdminActionRequired()
    const result = await fetchAdminActionRequired()

    expect(mockApi).toHaveBeenCalledWith('/api/v1/dashboard/admin-action-required')
    expect(result.totalPending).toBe(3)
    expect(result.items).toHaveLength(1)
    expect(result.items[0]).toEqual({
      domain: 'RESERVATION',
      scopeType: 'TEAM',
      scopeId: 10,
      scopeSlug: 'team-alpha',
      scopeName: 'チームA',
      itemId: '501',
      title: '予約: コート利用申請',
      requestedBy: '山田太郎',
      requestedAt: '2026-07-10T09:00:00',
      detailRoute: '/teams/team-alpha/admin/reservations/501',
    })
  })

  it('items が空配列のとき totalPending=0 のまま空配列を返す', async () => {
    mockApi.mockResolvedValueOnce({ data: { items: [], total_pending: 0 } })

    const { fetchAdminActionRequired } = usePersonalAdminActionRequired()
    const result = await fetchAdminActionRequired()

    expect(result.items).toEqual([])
    expect(result.totalPending).toBe(0)
  })
})
