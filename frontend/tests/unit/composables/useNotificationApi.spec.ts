import { describe, it, expect, beforeEach, vi } from 'vitest'

/**
 * useNotificationApi ユニットテスト。
 *
 * 検証観点:
 *   NOTIF-API-001: snooze が { snoozedUntil } を body に送る（F04.11 是正の回帰テスト）
 *   NOTIF-API-002: getNotifications → GET /api/v1/notifications?{qs}
 *   NOTIF-API-003: getUnreadCount → GET /api/v1/notifications/unread-count
 *   NOTIF-API-004: markAsRead → POST /api/v1/notifications/{id}/read
 *   NOTIF-API-005: markAllAsRead → POST /api/v1/notifications/read-all
 */

const mockFetch = vi.fn()

vi.mock('~/composables/useApi', () => ({
  useApi: () => mockFetch,
}))

const { useNotificationApi } = await import('~/composables/useNotificationApi')

describe('useNotificationApi', () => {
  beforeEach(() => {
    mockFetch.mockReset()
  })

  // ──────────────────────────────────────────────
  // NOTIF-API-001: snooze 回帰テスト（F04.11 是正）
  // ──────────────────────────────────────────────

  describe('NOTIF-API-001: snooze — { snoozedUntil } を body に送る', () => {
    it('snoozedUntil（ISO-8601 文字列）を body として POST する', async () => {
      mockFetch.mockResolvedValueOnce(undefined)
      const api = useNotificationApi()
      await api.snooze(123, '2026-06-01T12:00:00.000Z')

      expect(mockFetch).toHaveBeenCalledWith('/api/v1/notifications/123/snooze', {
        method: 'POST',
        body: { snoozedUntil: '2026-06-01T12:00:00.000Z' },
      })
    })

    it('body に snoozedUntil 以外のキーが混入しない', async () => {
      mockFetch.mockResolvedValueOnce(undefined)
      const api = useNotificationApi()
      await api.snooze(1, '2026-06-01T12:00:00.000Z')

      const calledBody = (mockFetch.mock.calls[0]![1] as Record<string, unknown>)['body'] as Record<string, unknown>
      expect(Object.keys(calledBody)).toEqual(['snoozedUntil'])
    })
  })

  // ──────────────────────────────────────────────
  // NOTIF-API-002: getNotifications
  // ──────────────────────────────────────────────

  describe('NOTIF-API-002: getNotifications', () => {
    it('GET /api/v1/notifications?{qs} を呼ぶ', async () => {
      mockFetch.mockResolvedValueOnce({ data: [], meta: {} })
      const api = useNotificationApi()
      await api.getNotifications({ limit: 20, isRead: false })

      const calledUrl = mockFetch.mock.calls[0]![0] as string
      expect(calledUrl).toMatch(/^\/api\/v1\/notifications\?/)
      expect(calledUrl).toContain('limit=20')
      expect(calledUrl).toContain('is_read=false')
    })

    it('引数なしでも呼べる', async () => {
      mockFetch.mockResolvedValueOnce({ data: [], meta: {} })
      const api = useNotificationApi()
      await api.getNotifications()

      expect(mockFetch).toHaveBeenCalledTimes(1)
    })
  })

  // ──────────────────────────────────────────────
  // NOTIF-API-003: getUnreadCount
  // ──────────────────────────────────────────────

  describe('NOTIF-API-003: getUnreadCount', () => {
    it('GET /api/v1/notifications/unread-count を呼ぶ', async () => {
      mockFetch.mockResolvedValueOnce({ data: { count: 5 } })
      const api = useNotificationApi()
      await api.getUnreadCount()

      expect(mockFetch).toHaveBeenCalledWith('/api/v1/notifications/unread-count')
    })
  })

  // ──────────────────────────────────────────────
  // NOTIF-API-004: markAsRead
  // ──────────────────────────────────────────────

  describe('NOTIF-API-004: markAsRead', () => {
    it('POST /api/v1/notifications/{id}/read を呼ぶ', async () => {
      mockFetch.mockResolvedValueOnce(undefined)
      const api = useNotificationApi()
      await api.markAsRead(42)

      expect(mockFetch).toHaveBeenCalledWith('/api/v1/notifications/42/read', {
        method: 'POST',
      })
    })
  })

  // ──────────────────────────────────────────────
  // NOTIF-API-005: markAllAsRead
  // ──────────────────────────────────────────────

  describe('NOTIF-API-005: markAllAsRead', () => {
    it('POST /api/v1/notifications/read-all を呼ぶ', async () => {
      mockFetch.mockResolvedValueOnce(undefined)
      const api = useNotificationApi()
      await api.markAllAsRead()

      expect(mockFetch).toHaveBeenCalledWith('/api/v1/notifications/read-all', {
        method: 'POST',
      })
    })
  })
})
