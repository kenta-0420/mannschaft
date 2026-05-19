/**
 * F09.17 Phase 11-c-4 — useSystemAdminAdCampaignApi のユニットテスト。
 *
 * <p>各メソッドが正しいパス・HTTP メソッド・クエリで API を呼ぶことを検証する。</p>
 */
import { describe, it, expect, beforeEach, vi } from 'vitest'

const mockFetch = vi.fn()

vi.mock('~/composables/useApi', () => ({
  useApi: () => mockFetch,
}))

const { useSystemAdminAdCampaignApi } = await import(
  '~/composables/useSystemAdminAdCampaignApi'
)

describe('useSystemAdminAdCampaignApi', () => {
  beforeEach(() => {
    mockFetch.mockReset()
  })

  describe('listReviewQueue()', () => {
    it('SYS-AD-CAMP-001: GET /review-queue を既定 page=0 size=20 で呼ぶ', async () => {
      mockFetch.mockResolvedValueOnce({
        data: [],
        meta: { page: 0, size: 20, totalElements: 0, totalPages: 0 },
      })
      const api = useSystemAdminAdCampaignApi()
      await api.listReviewQueue()

      expect(mockFetch).toHaveBeenCalledWith(
        '/api/v1/system-admin/ad-campaigns/review-queue',
        { params: { page: 0, size: 20 } },
      )
    })

    it('SYS-AD-CAMP-002: page/size を上書きできる', async () => {
      mockFetch.mockResolvedValueOnce({
        data: [],
        meta: { page: 2, size: 50, totalElements: 0, totalPages: 0 },
      })
      const api = useSystemAdminAdCampaignApi()
      await api.listReviewQueue({ page: 2, size: 50 })

      expect(mockFetch).toHaveBeenCalledWith(
        '/api/v1/system-admin/ad-campaigns/review-queue',
        { params: { page: 2, size: 50 } },
      )
    })
  })

  describe('getCampaignForReview()', () => {
    it('SYS-AD-CAMP-003: GET /ad-campaigns/{id} で詳細を取得する', async () => {
      mockFetch.mockResolvedValueOnce({
        data: {
          campaign: { id: 'abc', name: 'x', channels: [], audienceSegments: [] },
          detectedNgWords: [],
          moderationLogs: [],
        },
      })
      const api = useSystemAdminAdCampaignApi()
      await api.getCampaignForReview('abc')

      expect(mockFetch).toHaveBeenCalledWith(
        '/api/v1/system-admin/ad-campaigns/abc',
      )
    })
  })

  describe('approveCampaign()', () => {
    it('SYS-AD-CAMP-004: POST /approve を body なしで呼ぶ', async () => {
      mockFetch.mockResolvedValueOnce(undefined)
      const api = useSystemAdminAdCampaignApi()
      await api.approveCampaign('xyz')

      expect(mockFetch).toHaveBeenCalledWith(
        '/api/v1/system-admin/ad-campaigns/xyz/approve',
        expect.objectContaining({ method: 'POST', body: {} }),
      )
    })

    it('SYS-AD-CAMP-005: comment を渡すと body に乗る', async () => {
      mockFetch.mockResolvedValueOnce(undefined)
      const api = useSystemAdminAdCampaignApi()
      await api.approveCampaign('xyz', { comment: 'OK' })

      expect(mockFetch).toHaveBeenCalledWith(
        '/api/v1/system-admin/ad-campaigns/xyz/approve',
        expect.objectContaining({
          method: 'POST',
          body: { comment: 'OK' },
        }),
      )
    })
  })

  describe('blockCampaign()', () => {
    it('SYS-AD-CAMP-006: POST /block に reason を載せる', async () => {
      mockFetch.mockResolvedValueOnce(undefined)
      const api = useSystemAdminAdCampaignApi()
      await api.blockCampaign('xyz', { reason: '不適切表現' })

      expect(mockFetch).toHaveBeenCalledWith(
        '/api/v1/system-admin/ad-campaigns/xyz/block',
        expect.objectContaining({
          method: 'POST',
          body: { reason: '不適切表現' },
        }),
      )
    })
  })

  describe('listUserReports()', () => {
    it('SYS-AD-CAMP-007: GET /ad-user-reports を既定 page=0 size=20 で呼ぶ', async () => {
      mockFetch.mockResolvedValueOnce({
        data: [],
        meta: { page: 0, size: 20, totalElements: 0, totalPages: 0 },
      })
      const api = useSystemAdminAdCampaignApi()
      await api.listUserReports()

      expect(mockFetch).toHaveBeenCalledWith(
        '/api/v1/system-admin/ad-user-reports',
        expect.objectContaining({
          params: expect.objectContaining({ page: 0, size: 20 }),
        }),
      )
    })

    it('SYS-AD-CAMP-008: reason/status フィルタをクエリに乗せる', async () => {
      mockFetch.mockResolvedValueOnce({
        data: [],
        meta: { page: 0, size: 20, totalElements: 0, totalPages: 0 },
      })
      const api = useSystemAdminAdCampaignApi()
      await api.listUserReports({ reason: 'OFFENSIVE', status: 'NEW' })

      expect(mockFetch).toHaveBeenCalledWith(
        '/api/v1/system-admin/ad-user-reports',
        expect.objectContaining({
          params: expect.objectContaining({
            reason: 'OFFENSIVE',
            status: 'NEW',
          }),
        }),
      )
    })
  })
})
