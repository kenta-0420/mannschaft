import { describe, it, expect, beforeEach, vi } from 'vitest'

/**
 * F04.11 useInboxApi ユニットテスト。
 *
 * 検証観点:
 *   INBOX-API-001: buildQuery の配列展開（priority[] / sourceType[] が append で複数化）
 *   INBOX-API-002: 未指定パラメータはクエリに含まれない
 *   INBOX-API-003: getInbox → GET /api/v1/inbox?{qs}
 *   INBOX-API-004: getSummary → GET /api/v1/inbox/summary
 *   INBOX-API-005: snooze → POST /api/v1/inbox/snooze に { sourceType, sourceId, snoozedUntil }
 *   INBOX-API-006: unsnooze → POST /api/v1/inbox/unsnooze に { sourceType, sourceId }
 *   INBOX-API-007: archive → POST /api/v1/inbox/archive に { sourceType, sourceId }
 *   INBOX-API-008: unarchive → POST /api/v1/inbox/unarchive に { sourceType, sourceId }
 */

const mockFetch = vi.fn()

vi.mock('~/composables/useApi', () => ({
  useApi: () => mockFetch,
}))

const { useInboxApi } = await import('~/composables/useInboxApi')

describe('useInboxApi', () => {
  beforeEach(() => {
    mockFetch.mockReset()
  })

  // ──────────────────────────────────────────────
  // buildQuery: 配列展開
  // ──────────────────────────────────────────────

  describe('INBOX-API-001: buildQuery — priority[] / sourceType[] が append で展開される', () => {
    it('priority 配列が複数の同名パラメータになる', async () => {
      mockFetch.mockResolvedValueOnce({
        data: { items: [], page: 0, size: 20, totalEstimated: 0, hasMore: false },
      })
      const api = useInboxApi()
      await api.getInbox({ state: 'INBOX', priority: ['URGENT', 'HIGH'] })

      const calledUrl = mockFetch.mock.calls[0]![0] as string
      expect(calledUrl).toContain('priority=URGENT')
      expect(calledUrl).toContain('priority=HIGH')
    })

    it('sourceType 配列が複数の同名パラメータになる', async () => {
      mockFetch.mockResolvedValueOnce({
        data: { items: [], page: 0, size: 20, totalEstimated: 0, hasMore: false },
      })
      const api = useInboxApi()
      await api.getInbox({ sourceType: ['NOTIFICATION', 'MENTION'] })

      const calledUrl = mockFetch.mock.calls[0]![0] as string
      expect(calledUrl).toContain('sourceType=NOTIFICATION')
      expect(calledUrl).toContain('sourceType=MENTION')
    })
  })

  describe('INBOX-API-002: undefined / null のパラメータはスキップされる', () => {
    it('priority 未指定のとき URL に priority が含まれない', async () => {
      mockFetch.mockResolvedValueOnce({
        data: { items: [], page: 0, size: 20, totalEstimated: 0, hasMore: false },
      })
      const api = useInboxApi()
      await api.getInbox({ state: 'INBOX' })

      const calledUrl = mockFetch.mock.calls[0]![0] as string
      expect(calledUrl).not.toContain('priority')
    })

    it('空配列のとき URL に配列キーが含まれない', async () => {
      mockFetch.mockResolvedValueOnce({
        data: { items: [], page: 0, size: 20, totalEstimated: 0, hasMore: false },
      })
      const api = useInboxApi()
      // 空配列は undefined と同様にスキップされる（append ループが実行されない）
      await api.getInbox({ state: 'INBOX', priority: [] })

      const calledUrl = mockFetch.mock.calls[0]![0] as string
      expect(calledUrl).not.toContain('priority=')
    })
  })

  // ──────────────────────────────────────────────
  // getInbox
  // ──────────────────────────────────────────────

  describe('INBOX-API-003: getInbox', () => {
    it('GET /api/v1/inbox?{qs} を呼ぶ', async () => {
      mockFetch.mockResolvedValueOnce({
        data: { items: [], page: 0, size: 20, totalEstimated: 0, hasMore: false },
      })
      const api = useInboxApi()
      await api.getInbox({ state: 'SNOOZED', page: 0, size: 20 })

      const calledUrl = mockFetch.mock.calls[0]![0] as string
      expect(calledUrl).toMatch(/^\/api\/v1\/inbox\?/)
      expect(calledUrl).toContain('state=SNOOZED')
      expect(calledUrl).toContain('page=0')
      expect(calledUrl).toContain('size=20')
    })

    it('引数なしでもエラーなく呼べる', async () => {
      mockFetch.mockResolvedValueOnce({
        data: { items: [], page: 0, size: 20, totalEstimated: 0, hasMore: false },
      })
      const api = useInboxApi()
      await api.getInbox()

      expect(mockFetch).toHaveBeenCalledTimes(1)
    })
  })

  // ──────────────────────────────────────────────
  // getSummary
  // ──────────────────────────────────────────────

  describe('INBOX-API-004: getSummary', () => {
    it('GET /api/v1/inbox/summary を呼ぶ', async () => {
      mockFetch.mockResolvedValueOnce({
        data: { byState: {}, byPriority: {}, bySourceType: {} },
      })
      const api = useInboxApi()
      await api.getSummary()

      expect(mockFetch).toHaveBeenCalledWith('/api/v1/inbox/summary')
    })
  })

  // ──────────────────────────────────────────────
  // snooze
  // ──────────────────────────────────────────────

  describe('INBOX-API-005: snooze', () => {
    it('POST /api/v1/inbox/snooze に { sourceType, sourceId, snoozedUntil } を送る', async () => {
      mockFetch.mockResolvedValueOnce({ data: {} })
      const api = useInboxApi()
      await api.snooze('NOTIFICATION', 42, '2026-06-01T12:00:00Z')

      expect(mockFetch).toHaveBeenCalledWith('/api/v1/inbox/snooze', {
        method: 'POST',
        body: {
          sourceType: 'NOTIFICATION',
          sourceId: 42,
          snoozedUntil: '2026-06-01T12:00:00Z',
        },
      })
    })
  })

  // ──────────────────────────────────────────────
  // unsnooze
  // ──────────────────────────────────────────────

  describe('INBOX-API-006: unsnooze', () => {
    it('POST /api/v1/inbox/unsnooze に { sourceType, sourceId } を送る', async () => {
      mockFetch.mockResolvedValueOnce({ data: {} })
      const api = useInboxApi()
      await api.unsnooze('TODO_DUE', 7)

      expect(mockFetch).toHaveBeenCalledWith('/api/v1/inbox/unsnooze', {
        method: 'POST',
        body: { sourceType: 'TODO_DUE', sourceId: 7 },
      })
    })
  })

  // ──────────────────────────────────────────────
  // archive
  // ──────────────────────────────────────────────

  describe('INBOX-API-007: archive', () => {
    it('POST /api/v1/inbox/archive に { sourceType, sourceId } を送る', async () => {
      mockFetch.mockResolvedValueOnce({ data: {} })
      const api = useInboxApi()
      await api.archive('MENTION', 99)

      expect(mockFetch).toHaveBeenCalledWith('/api/v1/inbox/archive', {
        method: 'POST',
        body: { sourceType: 'MENTION', sourceId: 99 },
      })
    })
  })

  // ──────────────────────────────────────────────
  // unarchive
  // ──────────────────────────────────────────────

  describe('INBOX-API-008: unarchive', () => {
    it('POST /api/v1/inbox/unarchive に { sourceType, sourceId } を送る', async () => {
      mockFetch.mockResolvedValueOnce({ data: {} })
      const api = useInboxApi()
      await api.unarchive('CONFIRMABLE', 3)

      expect(mockFetch).toHaveBeenCalledWith('/api/v1/inbox/unarchive', {
        method: 'POST',
        body: { sourceType: 'CONFIRMABLE', sourceId: 3 },
      })
    })
  })

  // ──────────────────────────────────────────────
  // Phase 2: ラベル CRUD
  // ──────────────────────────────────────────────

  describe('INBOX-API-009: getLabels', () => {
    it('GET /api/v1/inbox/labels を呼ぶ', async () => {
      mockFetch.mockResolvedValueOnce({ data: [] })
      const api = useInboxApi()
      await api.getLabels()

      expect(mockFetch).toHaveBeenCalledWith('/api/v1/inbox/labels')
    })
  })

  describe('INBOX-API-010: createLabel', () => {
    it('POST /api/v1/inbox/labels に payload を送る', async () => {
      mockFetch.mockResolvedValueOnce({ data: { id: 'l1', name: 'test', color: '#fff', icon: null, sortOrder: 0 } })
      const api = useInboxApi()
      await api.createLabel({ name: 'test', color: '#fff' })

      expect(mockFetch).toHaveBeenCalledWith('/api/v1/inbox/labels', {
        method: 'POST',
        body: { name: 'test', color: '#fff' },
      })
    })
  })

  describe('INBOX-API-011: updateLabel', () => {
    it('PUT /api/v1/inbox/labels/{id} に payload を送る', async () => {
      mockFetch.mockResolvedValueOnce({ data: {} })
      const api = useInboxApi()
      await api.updateLabel('label-1', { name: '更新後', sortOrder: 1 })

      expect(mockFetch).toHaveBeenCalledWith('/api/v1/inbox/labels/label-1', {
        method: 'PUT',
        body: { name: '更新後', sortOrder: 1 },
      })
    })
  })

  describe('INBOX-API-012: deleteLabel', () => {
    it('DELETE /api/v1/inbox/labels/{id} を呼ぶ', async () => {
      mockFetch.mockResolvedValueOnce(undefined)
      const api = useInboxApi()
      await api.deleteLabel('label-1')

      expect(mockFetch).toHaveBeenCalledWith('/api/v1/inbox/labels/label-1', {
        method: 'DELETE',
      })
    })
  })

  describe('INBOX-API-013: assignLabel', () => {
    it('POST /api/v1/inbox/labels/{id}/assign に { sourceType, sourceId } を送る', async () => {
      mockFetch.mockResolvedValueOnce({})
      const api = useInboxApi()
      await api.assignLabel('label-1', 'NOTIFICATION', 42)

      expect(mockFetch).toHaveBeenCalledWith('/api/v1/inbox/labels/label-1/assign', {
        method: 'POST',
        body: { sourceType: 'NOTIFICATION', sourceId: 42 },
      })
    })
  })

  describe('INBOX-API-014: unassignLabel', () => {
    it('DELETE /api/v1/inbox/labels/{id}/assign に { sourceType, sourceId } を送る', async () => {
      mockFetch.mockResolvedValueOnce(undefined)
      const api = useInboxApi()
      await api.unassignLabel('label-1', 'NOTIFICATION', 42)

      expect(mockFetch).toHaveBeenCalledWith('/api/v1/inbox/labels/label-1/assign', {
        method: 'DELETE',
        body: { sourceType: 'NOTIFICATION', sourceId: 42 },
      })
    })
  })

  // ──────────────────────────────────────────────
  // Phase 2: bulk
  // ──────────────────────────────────────────────

  describe('INBOX-API-015: bulkAction', () => {
    it('POST /api/v1/inbox/bulk に payload を送る（ARCHIVE）', async () => {
      mockFetch.mockResolvedValueOnce({ data: { processed: 1, skipped: 0 } })
      const api = useInboxApi()
      await api.bulkAction({
        action: 'ARCHIVE',
        items: [{ sourceType: 'NOTIFICATION', sourceId: 1 }],
      })

      expect(mockFetch).toHaveBeenCalledWith('/api/v1/inbox/bulk', {
        method: 'POST',
        body: {
          action: 'ARCHIVE',
          items: [{ sourceType: 'NOTIFICATION', sourceId: 1 }],
          snoozedUntil: undefined,
          labelId: undefined,
        },
      })
    })

    it('LABEL_ADD 時に labelId が payload に含まれる', async () => {
      mockFetch.mockResolvedValueOnce({ data: { processed: 2, skipped: 0 } })
      const api = useInboxApi()
      await api.bulkAction({
        action: 'LABEL_ADD',
        items: [{ sourceType: 'NOTIFICATION', sourceId: 1 }],
        labelId: 'label-1',
      })

      const body = mockFetch.mock.calls[0]![1].body
      expect(body.action).toBe('LABEL_ADD')
      expect(body.labelId).toBe('label-1')
    })
  })
})
