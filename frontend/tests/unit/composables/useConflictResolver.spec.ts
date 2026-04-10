import { describe, it, expect, vi, beforeEach } from 'vitest'

/**
 * F11.1 Phase 5: useConflictResolver のユニットテスト。
 *
 * useApi をモックし、各メソッドが正しいエンドポイント・パラメータで
 * API を呼び出すことを検証する。
 */

const mockApi = vi.fn()

vi.mock('#app', async (importOriginal) => {
  const mod = await importOriginal<Record<string, unknown>>()
  return {
    ...mod,
    useApi: () => mockApi,
  }
})

// useConflictResolver は Nuxt の auto-import に依存するため、
// テスト内で直接 import する
const { useConflictResolver } = await import('~/composables/useConflictResolver')

describe('useConflictResolver', () => {
  beforeEach(() => {
    mockApi.mockReset()
  })

  describe('getMyConflicts', () => {
    it('デフォルトパラメータで一覧を取得できる', async () => {
      mockApi.mockResolvedValueOnce({
        data: [
          {
            id: 1,
            resource_type: 'ATTENDANCE_RESPONSE',
            resource_id: 10,
            client_version: 3,
            server_version: 5,
            resolution: null,
            resolved_at: null,
            created_at: '2026-04-10T10:00:00',
          },
        ],
        meta: { page: 0, size: 20, totalElements: 1, totalPages: 1 },
      })

      const { getMyConflicts } = useConflictResolver()
      const result = await getMyConflicts()

      expect(mockApi).toHaveBeenCalledWith('/api/v1/sync/conflicts/me?page=0&size=20')
      expect(result.data).toHaveLength(1)
      expect(result.data[0].resourceType).toBe('ATTENDANCE_RESPONSE')
      expect(result.data[0].resourceId).toBe(10)
      expect(result.meta.totalElements).toBe(1)
    })

    it('ページ番号を指定して取得できる', async () => {
      mockApi.mockResolvedValueOnce({
        data: [],
        meta: { page: 2, size: 20, totalElements: 50, totalPages: 3 },
      })

      const { getMyConflicts } = useConflictResolver()
      await getMyConflicts(2)

      expect(mockApi).toHaveBeenCalledWith('/api/v1/sync/conflicts/me?page=2&size=20')
    })
  })

  describe('getConflictDetail', () => {
    it('コンフリクト詳細を取得しキャメルケースに変換する', async () => {
      mockApi.mockResolvedValueOnce({
        data: {
          id: 42,
          user_id: 1,
          resource_type: 'ATTENDANCE_RESPONSE',
          resource_id: 20,
          client_data: '{"status":"PRESENT"}',
          server_data: '{"status":"ABSENT"}',
          client_version: 3,
          server_version: 5,
          resolution: null,
          resolved_at: null,
          created_at: '2026-04-10T10:00:00',
          updated_at: '2026-04-10T10:00:00',
        },
      })

      const { getConflictDetail } = useConflictResolver()
      const result = await getConflictDetail(42)

      expect(mockApi).toHaveBeenCalledWith('/api/v1/sync/conflicts/42')
      expect(result.id).toBe(42)
      expect(result.userId).toBe(1)
      expect(result.resourceType).toBe('ATTENDANCE_RESPONSE')
      expect(result.clientData).toEqual({ status: 'PRESENT' })
      expect(result.serverData).toEqual({ status: 'ABSENT' })
    })

    it('client_data がオブジェクトの場合もそのまま扱える', async () => {
      mockApi.mockResolvedValueOnce({
        data: {
          id: 1,
          user_id: 1,
          resource_type: 'TEST',
          resource_id: 1,
          client_data: { key: 'value' },
          server_data: { key: 'other' },
          client_version: 1,
          server_version: 2,
          resolution: null,
          resolved_at: null,
          created_at: '2026-04-10T10:00:00',
          updated_at: '2026-04-10T10:00:00',
        },
      })

      const { getConflictDetail } = useConflictResolver()
      const result = await getConflictDetail(1)

      expect(result.clientData).toEqual({ key: 'value' })
    })
  })

  describe('resolveConflict', () => {
    it('CLIENT_WIN で解決できる', async () => {
      mockApi.mockResolvedValueOnce({
        data: {
          id: 42,
          user_id: 1,
          resource_type: 'ATTENDANCE_RESPONSE',
          resource_id: 20,
          client_data: '{"status":"PRESENT"}',
          server_data: '{"status":"ABSENT"}',
          client_version: 3,
          server_version: 5,
          resolution: 'CLIENT_WIN',
          resolved_at: '2026-04-10T11:00:00',
          created_at: '2026-04-10T10:00:00',
          updated_at: '2026-04-10T11:00:00',
        },
      })

      const { resolveConflict } = useConflictResolver()
      const result = await resolveConflict(42, { resolution: 'CLIENT_WIN' })

      expect(mockApi).toHaveBeenCalledWith('/api/v1/sync/conflicts/42/resolve', {
        method: 'PATCH',
        body: { resolution: 'CLIENT_WIN' },
      })
      expect(result.resolution).toBe('CLIENT_WIN')
    })

    it('SERVER_WIN で解決できる', async () => {
      mockApi.mockResolvedValueOnce({
        data: {
          id: 42,
          user_id: 1,
          resource_type: 'TEST',
          resource_id: 1,
          client_data: '{}',
          server_data: '{}',
          client_version: 1,
          server_version: 2,
          resolution: 'SERVER_WIN',
          resolved_at: '2026-04-10T11:00:00',
          created_at: '2026-04-10T10:00:00',
          updated_at: '2026-04-10T11:00:00',
        },
      })

      const { resolveConflict } = useConflictResolver()
      await resolveConflict(42, { resolution: 'SERVER_WIN' })

      expect(mockApi).toHaveBeenCalledWith('/api/v1/sync/conflicts/42/resolve', {
        method: 'PATCH',
        body: { resolution: 'SERVER_WIN' },
      })
    })
  })

  describe('discardConflict', () => {
    it('コンフリクトを破棄できる', async () => {
      mockApi.mockResolvedValueOnce(undefined)

      const { discardConflict } = useConflictResolver()
      await discardConflict(42)

      expect(mockApi).toHaveBeenCalledWith('/api/v1/sync/conflicts/42', {
        method: 'DELETE',
      })
    })
  })
})
