import { describe, it, expect, beforeEach, vi } from 'vitest'

/**
 * F08.7 Phase 10-γ: useShiftBudgetApi のユニットテスト。
 *
 * <p>主要メソッドが正しいパス・メソッド・ヘッダ・ボディで API を呼ぶことを検証する。
 * すべての CRUD/警告/失敗イベント/月次締め API について最小1ケースずつ確認。</p>
 */

const mockFetch = vi.fn()
vi.mock('~/composables/useApi', () => ({
  useApi: () => mockFetch,
}))

const { useShiftBudgetApi } = await import('~/composables/useShiftBudgetApi')

describe('useShiftBudgetApi', () => {
  beforeEach(() => {
    mockFetch.mockReset()
  })

  describe('calculateRequiredSlots', () => {
    it('POST /calc/required-slots に request body をそのまま渡す', async () => {
      mockFetch.mockResolvedValueOnce({
        data: {
          budget_amount: 300000,
          avg_hourly_rate: 1200,
          slot_hours: 4,
          required_slots: 62,
          calculation: 'floor(300000 / (1200 × 4)) = 62',
          warnings: [],
        },
      })
      const api = useShiftBudgetApi()
      const res = await api.calculateRequiredSlots({
        team_id: 1,
        budget_amount: 300000,
        slot_hours: 4,
        rate_mode: 'MEMBER_AVG',
      })

      expect(mockFetch).toHaveBeenCalledWith(
        '/api/v1/shift-budget/calc/required-slots',
        expect.objectContaining({
          method: 'POST',
          body: expect.objectContaining({
            team_id: 1,
            budget_amount: 300000,
            slot_hours: 4,
            rate_mode: 'MEMBER_AVG',
          }),
        }),
      )
      expect(res.required_slots).toBe(62)
    })
  })

  describe('listAllocations', () => {
    it('GET /allocations に X-Organization-Id ヘッダを付与し、ページング QS を含める', async () => {
      mockFetch.mockResolvedValueOnce({
        data: { items: [], page: 0, size: 20, total: 0 },
      })
      const api = useShiftBudgetApi()
      await api.listAllocations(42, 1, 50)

      expect(mockFetch).toHaveBeenCalledWith(
        '/api/v1/shift-budget/allocations?page=1&size=50',
        expect.objectContaining({
          headers: { 'X-Organization-Id': '42' },
        }),
      )
    })
  })

  describe('createAllocation', () => {
    it('POST /allocations に X-Organization-Id ヘッダ + body を渡す', async () => {
      const allocation = {
        id: 1,
        organization_id: 42,
        team_id: null,
        project_id: null,
        fiscal_year_id: 1,
        budget_category_id: 2,
        period_start: '2026-04-01',
        period_end: '2026-04-30',
        allocated_amount: 300000,
        consumed_amount: 0,
        confirmed_amount: 0,
        currency: 'JPY',
        note: null,
        created_by: 1,
        version: 1,
        created_at: '',
        updated_at: '',
      }
      mockFetch.mockResolvedValueOnce({ data: allocation })
      const api = useShiftBudgetApi()
      const res = await api.createAllocation(42, {
        fiscal_year_id: 1,
        budget_category_id: 2,
        period_start: '2026-04-01',
        period_end: '2026-04-30',
        allocated_amount: 300000,
      })

      expect(mockFetch).toHaveBeenCalledWith(
        '/api/v1/shift-budget/allocations',
        expect.objectContaining({
          method: 'POST',
          headers: { 'X-Organization-Id': '42' },
          body: expect.objectContaining({ allocated_amount: 300000 }),
        }),
      )
      expect(res.id).toBe(1)
    })
  })

  describe('updateAllocation', () => {
    it('PUT /allocations/{id} に楽観ロック version を含む body を渡す', async () => {
      mockFetch.mockResolvedValueOnce({ data: { id: 1, version: 2 } })
      const api = useShiftBudgetApi()
      await api.updateAllocation(42, 1, {
        allocated_amount: 350000,
        note: '更新',
        version: 1,
      })

      expect(mockFetch).toHaveBeenCalledWith(
        '/api/v1/shift-budget/allocations/1',
        expect.objectContaining({
          method: 'PUT',
          headers: { 'X-Organization-Id': '42' },
          body: expect.objectContaining({ version: 1 }),
        }),
      )
    })
  })

  describe('deleteAllocation', () => {
    it('DELETE /allocations/{id} を呼ぶ', async () => {
      mockFetch.mockResolvedValueOnce(undefined)
      const api = useShiftBudgetApi()
      await api.deleteAllocation(42, 99)

      expect(mockFetch).toHaveBeenCalledWith(
        '/api/v1/shift-budget/allocations/99',
        expect.objectContaining({
          method: 'DELETE',
          headers: { 'X-Organization-Id': '42' },
        }),
      )
    })
  })

  describe('getConsumptionSummary', () => {
    it('GET /allocations/{id}/consumption-summary を呼ぶ', async () => {
      mockFetch.mockResolvedValueOnce({
        data: {
          allocation_id: 1,
          allocated_amount: 300000,
          consumed_amount: 50000,
          status: 'OK',
          flags: [],
        },
      })
      const api = useShiftBudgetApi()
      const res = await api.getConsumptionSummary(42, 1)

      expect(mockFetch).toHaveBeenCalledWith(
        '/api/v1/shift-budget/allocations/1/consumption-summary',
        expect.objectContaining({
          headers: { 'X-Organization-Id': '42' },
        }),
      )
      expect(res.status).toBe('OK')
    })
  })

  describe('listAlerts', () => {
    it('GET /alerts にページング QS を含めて呼ぶ', async () => {
      mockFetch.mockResolvedValueOnce({ data: [] })
      const api = useShiftBudgetApi()
      await api.listAlerts(42, 0, 20)

      expect(mockFetch).toHaveBeenCalledWith(
        '/api/v1/shift-budget/alerts?page=0&size=20',
        expect.objectContaining({
          headers: { 'X-Organization-Id': '42' },
        }),
      )
    })
  })

  describe('acknowledgeAlert', () => {
    it('POST /alerts/{id}/acknowledge にコメント付きで呼ぶ', async () => {
      mockFetch.mockResolvedValueOnce({ data: { id: 1, acknowledged_at: '2026-05-03T00:00:00' } })
      const api = useShiftBudgetApi()
      await api.acknowledgeAlert(42, 1, { comment: '確認しました' })

      expect(mockFetch).toHaveBeenCalledWith(
        '/api/v1/shift-budget/alerts/1/acknowledge',
        expect.objectContaining({
          method: 'POST',
          headers: { 'X-Organization-Id': '42' },
          body: { comment: '確認しました' },
        }),
      )
    })

    it('comment 省略時は空オブジェクトを送る', async () => {
      mockFetch.mockResolvedValueOnce({ data: { id: 1 } })
      const api = useShiftBudgetApi()
      await api.acknowledgeAlert(42, 1)

      expect(mockFetch).toHaveBeenCalledWith(
        '/api/v1/shift-budget/alerts/1/acknowledge',
        expect.objectContaining({ body: {} }),
      )
    })
  })

  describe('executeMonthlyClose', () => {
    it('POST /monthly-close に organization_id と year_month を body で渡す（X-Organization-Id ヘッダは付けない）', async () => {
      mockFetch.mockResolvedValueOnce({
        data: {
          year_month: '2026-04',
          closed_allocations: 3,
          already_closed_allocations: 0,
          closed_consumptions: 50,
          processed_organization_ids: [42],
          failed_organization_ids: [],
          already_closed_organization_ids: [],
        },
      })
      const api = useShiftBudgetApi()
      await api.executeMonthlyClose({ organization_id: 42, year_month: '2026-04' })

      expect(mockFetch).toHaveBeenCalledWith(
        '/api/v1/shift-budget/monthly-close',
        expect.objectContaining({
          method: 'POST',
          body: { organization_id: 42, year_month: '2026-04' },
        }),
      )
      // monthly-close は X-Organization-Id ヘッダを使わないため、headers が無いか {} のいずれか
      const call = mockFetch.mock.calls[0]
      expect(call).toBeDefined()
      const opts = call?.[1] as { headers?: Record<string, string> } | undefined
      expect(opts?.headers).toBeUndefined()
    })
  })

  describe('listFailedEvents', () => {
    it('status 指定時は QS に含める', async () => {
      mockFetch.mockResolvedValueOnce({ data: [] })
      const api = useShiftBudgetApi()
      await api.listFailedEvents(42, 'PENDING', 0, 20)

      expect(mockFetch).toHaveBeenCalledWith(
        '/api/v1/shift-budget/failed-events?status=PENDING&page=0&size=20',
        expect.objectContaining({
          headers: { 'X-Organization-Id': '42' },
        }),
      )
    })

    it('status 未指定時は QS に含めない', async () => {
      mockFetch.mockResolvedValueOnce({ data: [] })
      const api = useShiftBudgetApi()
      await api.listFailedEvents(42, null, 0, 20)

      expect(mockFetch).toHaveBeenCalledWith(
        '/api/v1/shift-budget/failed-events?page=0&size=20',
        expect.any(Object),
      )
    })
  })

  describe('retryFailedEvent', () => {
    it('POST /failed-events/{id}/retry を呼ぶ', async () => {
      mockFetch.mockResolvedValueOnce({ data: { id: 1, status: 'RETRYING' } })
      const api = useShiftBudgetApi()
      await api.retryFailedEvent(42, 1)

      expect(mockFetch).toHaveBeenCalledWith(
        '/api/v1/shift-budget/failed-events/1/retry',
        expect.objectContaining({
          method: 'POST',
          headers: { 'X-Organization-Id': '42' },
        }),
      )
    })
  })

  describe('resolveFailedEvent', () => {
    it('POST /failed-events/{id}/resolve を呼ぶ', async () => {
      mockFetch.mockResolvedValueOnce({ data: { id: 1, status: 'MANUAL_RESOLVED' } })
      const api = useShiftBudgetApi()
      await api.resolveFailedEvent(42, 1)

      expect(mockFetch).toHaveBeenCalledWith(
        '/api/v1/shift-budget/failed-events/1/resolve',
        expect.objectContaining({
          method: 'POST',
          headers: { 'X-Organization-Id': '42' },
        }),
      )
    })
  })

  describe('createTodoBudgetLink', () => {
    it('POST /api/v1/todo-budget/links を組織ヘッダ付きで呼ぶ', async () => {
      mockFetch.mockResolvedValueOnce({
        data: { id: 1, project_id: 10, allocation_id: 1, currency: 'JPY' },
      })
      const api = useShiftBudgetApi()
      await api.createTodoBudgetLink(42, {
        project_id: 10,
        allocation_id: 1,
        link_amount: 50000,
      })

      expect(mockFetch).toHaveBeenCalledWith(
        '/api/v1/todo-budget/links',
        expect.objectContaining({
          method: 'POST',
          headers: { 'X-Organization-Id': '42' },
        }),
      )
    })
  })
})
