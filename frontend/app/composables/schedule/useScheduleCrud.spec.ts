import { beforeEach, describe, expect, it, vi } from 'vitest'

const mockApi = vi.fn()

vi.mock('~/composables/useApi', () => ({
  useApi: () => mockApi,
}))
vi.mock('~/composables/useDatetime', () => ({
  useDatetime: () => ({
    buildDayStartStr: (d: string) => `${d}T00:00:00`,
    buildDayEndStr: (d: string) => `${d}T23:59:59`,
  }),
}))

const { useScheduleCrud } = await import('./useScheduleCrud')

describe('カレンダーレイヤー設定 API（F03.19 §4.4/§4.5）', () => {
  beforeEach(() => {
    mockApi.mockReset()
    mockApi.mockResolvedValue({ data: {} })
  })

  it('色のみの PATCH では hidden を送らない（AC-08b の部分更新セマンティクス）', async () => {
    await useScheduleCrud().updateMyCalendarLayer('TEAM', 42, { color: '#7C3AED' })

    expect(mockApi).toHaveBeenCalledWith(
      '/api/v1/me/calendar-layers/TEAM/42',
      { method: 'PATCH', body: { color: '#7C3AED' } },
    )
    const body = mockApi.mock.calls[0]?.[1]?.body as Record<string, unknown>
    expect('hidden' in body).toBe(false)
  })

  it('hidden のみの PATCH では color を送らない（色設定を保持する）', async () => {
    await useScheduleCrud().updateMyCalendarLayer('ORGANIZATION', 7, { hidden: true })

    expect(mockApi).toHaveBeenCalledWith(
      '/api/v1/me/calendar-layers/ORGANIZATION/7',
      { method: 'PATCH', body: { hidden: true } },
    )
    const body = mockApi.mock.calls[0]?.[1]?.body as Record<string, unknown>
    expect('color' in body).toBe(false)
  })

  it('PERSONAL の scopeId 0 をパスに含める（§4.3.1）', async () => {
    await useScheduleCrud().updateMyCalendarLayer('PERSONAL', 0, { color: '#0284C7' })

    expect(mockApi).toHaveBeenCalledWith(
      '/api/v1/me/calendar-layers/PERSONAL/0',
      { method: 'PATCH', body: { color: '#0284C7' } },
    )
  })

  it('自動色へ戻すのは DELETE（§4.5）', async () => {
    await useScheduleCrud().deleteMyCalendarLayer('TEAM', 42)

    expect(mockApi).toHaveBeenCalledWith(
      '/api/v1/me/calendar-layers/TEAM/42',
      { method: 'DELETE' },
    )
  })
})

describe('共有予定の更新範囲（CMP-107）', () => {
  beforeEach(() => {
    mockApi.mockReset()
    mockApi.mockResolvedValue({ data: {} })
  })

  it('THIS_AND_FOLLOWING を updateScope クエリとして PATCH する', async () => {
    await useScheduleCrud().updateSchedule(
      'team',
      'fc-u-18',
      123,
      { title: '以降も更新' },
      'THIS_AND_FOLLOWING',
    )

    expect(mockApi).toHaveBeenCalledWith(
      '/api/v1/teams/fc-u-18/schedules/123?updateScope=THIS_AND_FOLLOWING',
      { method: 'PATCH', body: { title: '以降も更新' } },
    )
  })

  it('範囲未指定は後方互換のためクエリを付けない', async () => {
    await useScheduleCrud().updateSchedule('organization', 'jfa', 456, { title: '単体更新' })

    expect(mockApi).toHaveBeenCalledWith(
      '/api/v1/organizations/jfa/schedules/456',
      { method: 'PATCH', body: { title: '単体更新' } },
    )
  })
})
