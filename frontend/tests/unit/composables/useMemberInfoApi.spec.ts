import { describe, it, expect, beforeEach, vi } from 'vitest'

/**
 * useMemberInfoApi ユニットテスト。
 *
 * モック方針:
 *  - `useApi` を vi.mock でスタブ化し、mockFetch を差し込む。
 *  - 各メソッドが正しいパス・メソッド・ボディで呼び出されることを検証する。
 *
 * テストケース一覧:
 *  MI-API-001: getFields — /teams/{teamId}/member-info/fields を GET する
 *  MI-API-002: createField — /teams/{teamId}/member-info/fields に POST する
 *  MI-API-003: updateField — /teams/{teamId}/member-info/fields/{fieldId} に PUT する
 *  MI-API-004: deleteField — /teams/{teamId}/member-info/fields/{fieldId} を DELETE する
 *  MI-API-005: reorderFields — /teams/{teamId}/member-info/fields/reorder に PUT する
 *  MI-API-006: getResponseStatus — /teams/{teamId}/member-info/responses/status を GET する
 *  MI-API-007: sendRemind — /teams/{teamId}/member-info/responses/{userId}/remind に POST する
 *  MI-API-008: getMyResponses — /teams/{teamId}/member-info/responses/me を GET する
 *  MI-API-009: upsertMyResponses — /teams/{teamId}/member-info/responses/me に PUT する
 */

const mockFetch = vi.fn()

vi.mock('~/composables/useApi', () => ({
  useApi: () => mockFetch,
}))

const { useMemberInfoApi } = await import('~/composables/useMemberInfoApi')

describe('useMemberInfoApi', () => {
  beforeEach(() => {
    mockFetch.mockReset()
  })

  it('MI-API-001: getFields — /teams/{teamId}/member-info/fields を GET する', async () => {
    mockFetch.mockResolvedValueOnce({ data: [] })
    const api = useMemberInfoApi()

    await api.getFields('1')

    expect(mockFetch).toHaveBeenCalledWith('/api/v1/teams/1/member-info/fields')
  })

  it('MI-API-002: createField — /teams/{teamId}/member-info/fields に POST する', async () => {
    mockFetch.mockResolvedValueOnce({ data: {} })
    const api = useMemberInfoApi()
    const req = {
      fieldName: '緊急連絡先',
      fieldType: 'PHONE' as const,
      isRequired: true,
      isSensitive: true,
      refreshIntervalMonths: 36,
    }

    await api.createField('1', req)

    expect(mockFetch).toHaveBeenCalledWith(
      '/api/v1/teams/1/member-info/fields',
      { method: 'POST', body: req },
    )
  })

  it('MI-API-003: updateField — /teams/{teamId}/member-info/fields/{fieldId} に PUT する', async () => {
    mockFetch.mockResolvedValueOnce({ data: {} })
    const api = useMemberInfoApi()
    const req = { fieldName: '更新後のフィールド名' }

    await api.updateField('1', 42, req)

    expect(mockFetch).toHaveBeenCalledWith(
      '/api/v1/teams/1/member-info/fields/42',
      { method: 'PUT', body: req },
    )
  })

  it('MI-API-004: deleteField — /teams/{teamId}/member-info/fields/{fieldId} を DELETE する', async () => {
    mockFetch.mockResolvedValueOnce(undefined)
    const api = useMemberInfoApi()

    await api.deleteField('1', 42)

    expect(mockFetch).toHaveBeenCalledWith(
      '/api/v1/teams/1/member-info/fields/42',
      { method: 'DELETE' },
    )
  })

  it('MI-API-005: reorderFields — /teams/{teamId}/member-info/fields/reorder に PUT する', async () => {
    mockFetch.mockResolvedValueOnce(undefined)
    const api = useMemberInfoApi()
    const req = { orders: [{ fieldId: 1, sortOrder: 0 }, { fieldId: 2, sortOrder: 1 }] }

    await api.reorderFields('1', req)

    expect(mockFetch).toHaveBeenCalledWith(
      '/api/v1/teams/1/member-info/fields/reorder',
      { method: 'PUT', body: req },
    )
  })

  it('MI-API-006: getResponseStatus — /teams/{teamId}/member-info/responses/status を GET する', async () => {
    mockFetch.mockResolvedValueOnce({ data: { totalMembers: 0, completedCount: 0, overdueCount: 0, members: [] } })
    const api = useMemberInfoApi()

    await api.getResponseStatus('1')

    expect(mockFetch).toHaveBeenCalledWith('/api/v1/teams/1/member-info/responses/status')
  })

  it('MI-API-007: sendRemind — /teams/{teamId}/member-info/responses/{userId}/remind に POST する', async () => {
    mockFetch.mockResolvedValueOnce(undefined)
    const api = useMemberInfoApi()

    await api.sendRemind('1', 99)

    expect(mockFetch).toHaveBeenCalledWith(
      '/api/v1/teams/1/member-info/responses/99/remind',
      { method: 'POST' },
    )
  })

  it('MI-API-008: getMyResponses — /teams/{teamId}/member-info/responses/me を GET する', async () => {
    mockFetch.mockResolvedValueOnce({ data: [] })
    const api = useMemberInfoApi()

    await api.getMyResponses('1')

    expect(mockFetch).toHaveBeenCalledWith('/api/v1/teams/1/member-info/responses/me')
  })

  it('MI-API-009: upsertMyResponses — /teams/{teamId}/member-info/responses/me に PUT する', async () => {
    mockFetch.mockResolvedValueOnce(undefined)
    const api = useMemberInfoApi()
    const req = { responses: [{ fieldId: 1, value: '090-1234-5678' }] }

    await api.upsertMyResponses('1', req)

    expect(mockFetch).toHaveBeenCalledWith(
      '/api/v1/teams/1/member-info/responses/me',
      { method: 'PUT', body: req },
    )
  })
})
