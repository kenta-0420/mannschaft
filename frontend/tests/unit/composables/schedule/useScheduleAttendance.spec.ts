import { describe, it, expect, beforeEach, vi } from 'vitest'

/**
 * useScheduleAttendance ユニットテスト（RSVP契約固定・回帰ガード）。
 *
 * <p>全画面の出欠回答（RSVP）が存在しないエンドポイント
 * `PUT /api/v1/{teams|organizations}/{scopeId}/schedules/{scheduleId}/attendances/me` を叩き
 * 常時 404 になっていた真因バグの再発防止として、respondAttendance が実在する BE エンドポイント
 * `PATCH /api/v1/schedules/{scheduleId}/responses` を、スコープに依存せず scheduleId のみで
 * 呼び出すことを固定する。</p>
 *
 * モック方針:
 *  - `useApi` を vi.mock でスタブ化し、mockFetch（関数）を差し込む（useFavoritesApi.spec.ts と同一流儀）。
 *
 * テストケース一覧:
 *  SA-001: respondAttendance — PATCH /api/v1/schedules/{scheduleId}/responses を { status, comment } で呼ぶ
 *  SA-002: respondAttendance — 旧エンドポイント（.../attendances/me）や PUT メソッドは一切呼ばない
 *  SA-003: respondAttendance — scopeType/scopeId が異なっても URL は scheduleId のみに依存する（スコープ非依存）
 */

const mockFetch = vi.fn()

vi.mock('~/composables/useApi', () => ({
  useApi: () => mockFetch,
}))

const { useScheduleAttendance } = await import('~/composables/schedule/useScheduleAttendance')

describe('useScheduleAttendance', () => {
  beforeEach(() => {
    mockFetch.mockReset()
  })

  describe('respondAttendance()', () => {
    it('SA-001: PATCH /api/v1/schedules/{scheduleId}/responses を { method: PATCH, body: { status } } で呼ぶ', async () => {
      mockFetch.mockResolvedValueOnce({ data: {} })
      const api = useScheduleAttendance()

      await api.respondAttendance('team', 'team-000092', 123, { status: 'ATTENDING' })

      expect(mockFetch).toHaveBeenCalledWith('/api/v1/schedules/123/responses', {
        method: 'PATCH',
        body: { status: 'ATTENDING' },
      })
    })

    it('SA-002: 旧エンドポイント(attendances/me)やPUTメソッドは一切呼ばれない', async () => {
      mockFetch.mockResolvedValueOnce({ data: {} })
      const api = useScheduleAttendance()

      await api.respondAttendance('team', 'team-000092', 123, { status: 'ATTENDING' })

      const [calledUrl, calledOptions] = mockFetch.mock.calls[0] as [string, { method: string }]
      expect(calledUrl).not.toContain('attendances/me')
      expect(calledOptions.method).not.toBe('PUT')
    })

    it('SA-003: scopeType/scopeIdが異なってもURLはscheduleIdのみに依存する（スコープ非依存）', async () => {
      mockFetch.mockResolvedValueOnce({ data: {} })
      const api = useScheduleAttendance()

      await api.respondAttendance('organization', 'org-999', 456, {
        status: 'ABSENT',
        comment: '欠席します',
      })

      expect(mockFetch).toHaveBeenCalledWith('/api/v1/schedules/456/responses', {
        method: 'PATCH',
        body: { status: 'ABSENT', comment: '欠席します' },
      })
    })
  })
})
