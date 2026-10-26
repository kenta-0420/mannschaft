import { describe, it, expect, beforeEach, vi } from 'vitest'

/**
 * F03.17 キープ（日付未定の予定）useScheduleKeepApi のユニットテスト。
 *
 * useApi() のネットワーク層をモックし、URL 組み立て・HTTP メソッドが
 * 設計書（docs/features/F03.17_schedule_keep.md §4 API 契約）どおりであることを検証する。
 * composable レベルのテストに留め、mountSuspended を要する重いコンポーネントテストは
 * 避ける（memory: FE vitest の赤の大半は5秒枠の構造問題）。
 */

const mockFetch = vi.fn()
vi.mock('~/composables/useApi', () => ({
  useApi: () => mockFetch,
}))

const { useScheduleKeepApi } = await import('~/composables/schedule/useScheduleKeep')

describe('useScheduleKeepApi', () => {
  beforeEach(() => {
    mockFetch.mockReset()
    mockFetch.mockResolvedValue({ data: [] })
  })

  it('listScheduleKeeps: チームスコープの既定 status=KEPT で一覧を取得する', async () => {
    const api = useScheduleKeepApi()
    await api.listScheduleKeeps('team', 'aoi-fc')

    expect(mockFetch).toHaveBeenCalledTimes(1)
    const url = mockFetch.mock.calls[0]![0] as string
    expect(url).toContain('/api/v1/teams/aoi-fc/schedule-keeps')
    expect(url).toContain('status=KEPT')
  })

  it('listScheduleKeeps: 組織スコープは organizations パスを組み立てる', async () => {
    const api = useScheduleKeepApi()
    await api.listScheduleKeeps('organization', 'my-org', 'ALL')

    const url = mockFetch.mock.calls[0]![0] as string
    expect(url).toContain('/api/v1/organizations/my-org/schedule-keeps')
    expect(url).toContain('status=ALL')
  })

  it('listScheduleKeeps: 個人スコープは /me パスを組み立てる（scopeId 不要）', async () => {
    const api = useScheduleKeepApi()
    await api.listScheduleKeeps('personal', undefined)

    const url = mockFetch.mock.calls[0]![0] as string
    expect(url).toContain('/api/v1/me/schedule-keeps')
  })

  it('createScheduleKeep: タイトルのみのボディで POST する（AC-01 ADHD 中核）', async () => {
    const api = useScheduleKeepApi()
    await api.createScheduleKeep('team', 'aoi-fc', { title: '夏合宿' })

    expect(mockFetch.mock.calls[0]![0]).toBe('/api/v1/teams/aoi-fc/schedule-keeps')
    expect(mockFetch.mock.calls[0]![1]).toMatchObject({
      method: 'POST',
      body: { title: '夏合宿' },
    })
  })

  it('convertScheduleKeep: keepId を含む convert エンドポイントへ POST する', async () => {
    const api = useScheduleKeepApi()
    await api.convertScheduleKeep('team', 'aoi-fc', 'keep-1', { startAt: '2026-08-15T00:00:00', allDay: true })

    expect(mockFetch.mock.calls[0]![0]).toBe('/api/v1/teams/aoi-fc/schedule-keeps/keep-1/convert')
    expect(mockFetch.mock.calls[0]![1]).toMatchObject({ method: 'POST' })
  })

  it.each(['revert', 'archive', 'restore'] as const)('%s: keepId を含む専用エンドポイントへ POST する', async (action) => {
    const api = useScheduleKeepApi()
    const fn = action === 'revert' ? api.revertScheduleKeep : action === 'archive' ? api.archiveScheduleKeep : api.restoreScheduleKeep
    await fn('team', 'aoi-fc', 'keep-1')

    expect(mockFetch.mock.calls[0]![0]).toBe(`/api/v1/teams/aoi-fc/schedule-keeps/keep-1/${action}`)
    expect(mockFetch.mock.calls[0]![1]).toMatchObject({ method: 'POST' })
  })

  it('reorderScheduleKeeps: orderedIds をボディに含めて POST する', async () => {
    const api = useScheduleKeepApi()
    await api.reorderScheduleKeeps('team', 'aoi-fc', ['keep-2', 'keep-1'])

    expect(mockFetch.mock.calls[0]![0]).toBe('/api/v1/teams/aoi-fc/schedule-keeps/reorder')
    expect(mockFetch.mock.calls[0]![1]).toMatchObject({
      method: 'POST',
      body: { orderedIds: ['keep-2', 'keep-1'] },
    })
  })

  it('updateScheduleKeep: PATCH で部分更新する', async () => {
    const api = useScheduleKeepApi()
    await api.updateScheduleKeep('team', 'aoi-fc', 'keep-1', { memo: '更新後メモ' })

    expect(mockFetch.mock.calls[0]![0]).toBe('/api/v1/teams/aoi-fc/schedule-keeps/keep-1')
    expect(mockFetch.mock.calls[0]![1]).toMatchObject({
      method: 'PATCH',
      body: { memo: '更新後メモ' },
    })
  })

  it('deleteScheduleKeep: DELETE で論理削除する', async () => {
    const api = useScheduleKeepApi()
    await api.deleteScheduleKeep('team', 'aoi-fc', 'keep-1')

    expect(mockFetch.mock.calls[0]![0]).toBe('/api/v1/teams/aoi-fc/schedule-keeps/keep-1')
    expect(mockFetch.mock.calls[0]![1]).toMatchObject({ method: 'DELETE' })
  })
})
