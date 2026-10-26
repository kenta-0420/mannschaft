import { beforeEach, describe, expect, it, vi } from 'vitest'
const mockApi = vi.fn()
vi.mock('~/composables/useApi', () => ({ useApi: () => mockApi }))
const { useActivityApi } = await import('./useActivityApi')

describe('活動記録の楽観ロック付き操作', () => {
  beforeEach(() => {
    mockApi.mockReset()
    mockApi.mockResolvedValue({ data: {} })
  })
  it('詳細で取得したversionを公開リクエストに送る', async () => {
    await useActivityApi().publishActivity(42, 7)
    expect(mockApi).toHaveBeenCalledWith('/api/v1/activities/42/publish', {
      method: 'POST',
      body: { version: 7 },
    })
  })
  it('下書き編集は日付・本文・fieldValuesとversionを同じ更新で送る', async () => {
    const body = {
      title: '編集',
      activityDate: '2026-10-07',
      activityEndDate: null,
      activityTimeStart: null,
      activityTimeEnd: null,
      description: '',
      visibility: 'MEMBERS_ONLY' as const,
      fieldValues: { score: 0, checked: false },
      version: 7,
    }
    await useActivityApi().updateActivity(42, body)
    expect(mockApi).toHaveBeenCalledWith('/api/v1/activities/42', { method: 'PUT', body })
  })
})
