import { describe, it, expect, beforeEach, vi } from 'vitest'

/**
 * F10.X 第三陣（丁組） — useSystemAdminBatchApi のユニットテスト。
 *
 * <p>主要メソッドが正しいパス・メソッド・クエリで API を呼ぶことを検証する。
 * 主な観点:</p>
 * <ul>
 *   <li>listBatches() — {@code GET /api/v1/system-admin/batch} を呼ぶ</li>
 *   <li>getStatus(name) — name を URL エンコードして status を取得</li>
 *   <li>trigger(name, { sync }) — sync クエリと ignoreResponseError を付ける</li>
 *   <li>trigger() — 409 / 500 ステータスでも本文を取り出す</li>
 *   <li>trigger() — 404 では Error をスローする</li>
 * </ul>
 */

const mockFetch = vi.fn()
const mockRaw = vi.fn()

vi.mock('~/composables/useApi', () => ({
  useApi: () => {
    const api = mockFetch as typeof mockFetch & {
      raw: typeof mockRaw
    }
    api.raw = mockRaw
    return api
  },
}))

const { useSystemAdminBatchApi } = await import('~/composables/useSystemAdminBatchApi')

describe('useSystemAdminBatchApi', () => {
  beforeEach(() => {
    mockFetch.mockReset()
    mockRaw.mockReset()
  })

  describe('listBatches()', () => {
    it('BATCH-API-001: GET /api/v1/system-admin/batch を呼び data 配列を返す', async () => {
      mockFetch.mockResolvedValueOnce({
        data: [
          {
            name: 'village-serendipity-daily',
            description: 'ご縁スコア日次集計',
            schedulerLockName: 'villageSerendipityBatch',
            lastStatus: 'SUCCESS',
            lastStartedAt: '2026-05-17T02:00:00',
          },
        ],
      })

      const api = useSystemAdminBatchApi()
      const res = await api.listBatches()

      expect(mockFetch).toHaveBeenCalledWith('/api/v1/system-admin/batch')
      expect(res.data).toHaveLength(1)
      expect(res.data[0]?.name).toBe('village-serendipity-daily')
      expect(res.data[0]?.lastStatus).toBe('SUCCESS')
    })
  })

  describe('getStatus()', () => {
    it('BATCH-API-002: GET /api/v1/system-admin/batch/{name}/status を呼ぶ', async () => {
      mockFetch.mockResolvedValueOnce({
        data: {
          name: 'village-serendipity-daily',
          lastJobLog: {
            id: 7,
            jobName: 'village-serendipity-daily',
            status: 'SUCCESS',
            startedAt: '2026-05-17T02:00:00',
            finishedAt: '2026-05-17T02:00:30',
            processedCount: 0,
            errorMessage: null,
            createdAt: '2026-05-17T02:00:30',
          },
        },
      })

      const api = useSystemAdminBatchApi()
      const res = await api.getStatus('village-serendipity-daily')

      expect(mockFetch).toHaveBeenCalledWith(
        '/api/v1/system-admin/batch/village-serendipity-daily/status',
      )
      expect(res.data.lastJobLog?.id).toBe(7)
    })

    it('BATCH-API-003: name に特殊文字があれば URL エンコードする', async () => {
      mockFetch.mockResolvedValueOnce({ data: { name: 'foo bar', lastJobLog: null } })
      const api = useSystemAdminBatchApi()
      await api.getStatus('foo bar')

      expect(mockFetch).toHaveBeenCalledWith('/api/v1/system-admin/batch/foo%20bar/status')
    })
  })

  describe('trigger()', () => {
    it('BATCH-API-004: 既定で sync=false を付け POST し、202 ACCEPTED を返す', async () => {
      mockRaw.mockResolvedValueOnce({
        status: 202,
        _data: {
          data: {
            name: 'village-serendipity-daily',
            status: 'ACCEPTED',
            jobLogId: null,
            message: '非同期実行を受け付けました',
          },
        },
      })
      const api = useSystemAdminBatchApi()
      const res = await api.trigger('village-serendipity-daily')

      expect(mockRaw).toHaveBeenCalledWith(
        '/api/v1/system-admin/batch/village-serendipity-daily/trigger?sync=false',
        expect.objectContaining({ method: 'POST', ignoreResponseError: true }),
      )
      expect(res.httpStatus).toBe(202)
      expect(res.data.status).toBe('ACCEPTED')
    })

    it('BATCH-API-005: sync=true を指定した場合、クエリに sync=true を付与し 200 COMPLETED を返す', async () => {
      mockRaw.mockResolvedValueOnce({
        status: 200,
        _data: {
          data: {
            name: 'foo',
            status: 'COMPLETED',
            jobLogId: 99,
            message: '同期実行が完了しました',
          },
        },
      })
      const api = useSystemAdminBatchApi()
      const res = await api.trigger('foo', { sync: true })

      expect(mockRaw).toHaveBeenCalledWith(
        '/api/v1/system-admin/batch/foo/trigger?sync=true',
        expect.objectContaining({ method: 'POST', ignoreResponseError: true }),
      )
      expect(res.httpStatus).toBe(200)
      expect(res.data.status).toBe('COMPLETED')
      expect(res.data.jobLogId).toBe(99)
    })

    it('BATCH-API-006: 409 LOCKED でも本文を取り出す（throw しない）', async () => {
      mockRaw.mockResolvedValueOnce({
        status: 409,
        _data: {
          data: {
            name: 'foo',
            status: 'LOCKED',
            jobLogId: null,
            message: '他インスタンスがロックを保持中',
          },
        },
      })
      const api = useSystemAdminBatchApi()
      const res = await api.trigger('foo')

      expect(res.httpStatus).toBe(409)
      expect(res.data.status).toBe('LOCKED')
    })

    it('BATCH-API-007: 500 FAILED でも本文を取り出す', async () => {
      mockRaw.mockResolvedValueOnce({
        status: 500,
        _data: {
          data: {
            name: 'foo',
            status: 'FAILED',
            jobLogId: 100,
            message: 'NullPointerException',
          },
        },
      })
      const api = useSystemAdminBatchApi()
      const res = await api.trigger('foo', { sync: true })

      expect(res.httpStatus).toBe(500)
      expect(res.data.status).toBe('FAILED')
      expect(res.data.message).toContain('NullPointerException')
    })

    it('BATCH-API-008: 404 では Error をスローする', async () => {
      mockRaw.mockResolvedValueOnce({
        status: 404,
        _data: null,
      })
      const api = useSystemAdminBatchApi()

      await expect(api.trigger('unknown-batch')).rejects.toThrow(
        'Batch endpoint not found: unknown-batch',
      )
    })

    it('BATCH-API-009: _data が空で 200 が返った場合、Error をスローする（防御的）', async () => {
      mockRaw.mockResolvedValueOnce({
        status: 200,
        _data: null,
      })
      const api = useSystemAdminBatchApi()

      await expect(api.trigger('foo')).rejects.toThrow(/Unexpected response/)
    })
  })
})
