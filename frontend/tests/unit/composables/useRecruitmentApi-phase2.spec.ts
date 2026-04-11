import { describe, it, expect, beforeEach, vi } from 'vitest'

/**
 * F03.11 Phase 2 useRecruitmentApi のユニットテスト。
 *
 * - getMyFeed: フィードアイテムを取得できる
 * - getMyListings: 参加予定一覧を取得できる
 * - getDistributionTargets: 配信対象を取得できる
 * - setDistributionTargets: 配信対象を設定できる
 * - confirmApplication: 申込を確定できる
 */

// useApi のモック
const mockFetch = vi.fn()
vi.mock('~/composables/useApi', () => ({
  useApi: () => mockFetch,
}))

const { useRecruitmentApi } = await import('~/composables/useRecruitmentApi')

describe('useRecruitmentApi - Phase 2', () => {
  beforeEach(() => {
    mockFetch.mockReset()
  })

  describe('getMyFeed', () => {
    it('フィードアイテムを取得できる', async () => {
      const feedItems = [
        {
          id: 1,
          categoryId: 1,
          categoryNameI18nKey: null,
          scopeId: 10,
          scopeType: 'TEAM',
          title: 'フットサル個人参加',
          description: null,
          participationType: 'INDIVIDUAL',
          startAt: '2026-05-01T10:00:00',
          endAt: '2026-05-01T12:00:00',
          applicationDeadline: '2026-04-30T23:59:59',
          capacity: 20,
          confirmedCount: 5,
          status: 'OPEN',
          visibility: 'SCOPE_ONLY',
          location: '東京都',
          imageUrl: null,
          paymentEnabled: false,
          price: null,
          createdAt: '2026-04-11T09:00:00',
        },
      ]
      mockFetch.mockResolvedValue({ data: feedItems })

      const api = useRecruitmentApi()
      const result = await api.getMyFeed()

      expect(mockFetch).toHaveBeenCalledWith('/api/v1/me/recruitment-feed')
      expect(result.data).toHaveLength(1)
      expect(result.data[0].title).toBe('フットサル個人参加')
    })
  })

  describe('getMyListings', () => {
    it('参加予定一覧を取得できる', async () => {
      const myListings = [
        {
          id: 100,
          listingId: 1,
          participantType: 'USER',
          userId: 5,
          teamId: null,
          appliedBy: 5,
          status: 'CONFIRMED',
          waitlistPosition: null,
          note: null,
          appliedAt: '2026-04-10T10:00:00',
          statusChangedAt: '2026-04-10T11:00:00',
        },
      ]
      mockFetch.mockResolvedValue({ data: myListings })

      const api = useRecruitmentApi()
      const result = await api.getMyListings()

      expect(mockFetch).toHaveBeenCalledWith('/api/v1/me/recruitment-listings')
      expect(result.data[0].status).toBe('CONFIRMED')
    })
  })

  describe('getDistributionTargets', () => {
    it('配信対象を取得できる', async () => {
      const targets = [
        { id: 1, listingId: 10, targetType: 'MEMBERS', createdAt: '2026-04-11T09:00:00' },
      ]
      mockFetch.mockResolvedValue({ data: targets })

      const api = useRecruitmentApi()
      const result = await api.getDistributionTargets(10)

      expect(mockFetch).toHaveBeenCalledWith('/api/v1/recruitment-listings/10/distribution-targets')
      expect(result.data[0].targetType).toBe('MEMBERS')
    })
  })

  describe('setDistributionTargets', () => {
    it('配信対象を設定できる', async () => {
      const targets = [
        { id: 1, listingId: 10, targetType: 'MEMBERS', createdAt: '2026-04-11T09:00:00' },
        { id: 2, listingId: 10, targetType: 'FOLLOWERS', createdAt: '2026-04-11T09:00:00' },
      ]
      mockFetch.mockResolvedValue({ data: targets })

      const api = useRecruitmentApi()
      const result = await api.setDistributionTargets(10, {
        targetTypes: ['MEMBERS', 'FOLLOWERS'],
      })

      expect(mockFetch).toHaveBeenCalledWith('/api/v1/recruitment-listings/10/distribution-targets', {
        method: 'PUT',
        body: { targetTypes: ['MEMBERS', 'FOLLOWERS'] },
      })
      expect(result.data).toHaveLength(2)
    })
  })

  describe('confirmApplication', () => {
    it('申込を確定できる', async () => {
      const participant = {
        id: 100,
        listingId: 1,
        participantType: 'USER',
        userId: 5,
        teamId: null,
        appliedBy: 5,
        status: 'CONFIRMED',
        waitlistPosition: null,
        note: null,
        appliedAt: '2026-04-10T10:00:00',
        statusChangedAt: '2026-04-11T10:00:00',
      }
      mockFetch.mockResolvedValue({ data: participant })

      const api = useRecruitmentApi()
      const result = await api.confirmApplication(1, 100)

      expect(mockFetch).toHaveBeenCalledWith(
        '/api/v1/recruitment-listings/1/participants/100/confirm',
        { method: 'POST' },
      )
      expect(result.data.status).toBe('CONFIRMED')
    })
  })
})
