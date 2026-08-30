import { beforeEach, describe, expect, it, vi } from 'vitest'

const mockApi = vi.fn()

vi.mock('~/composables/useApi', () => ({
  useApi: () => mockApi,
}))

const { useRecruitmentCrud } = await import('./useRecruitmentCrud')

describe('useRecruitmentCrud 個人市', () => {
  beforeEach(() => {
    mockApi.mockReset()
  })

  it('本人の札を全フィルタとページ条件付きで取得する', async () => {
    await useRecruitmentCrud().listMyMarketListings({
      status: 'DRAFT',
      prefectureCode: '13',
      cityCode: '13113',
      categoryId: 7,
      page: 2,
      size: 20,
    })

    expect(mockApi).toHaveBeenCalledWith(
      '/api/v1/me/market/listings?status=DRAFT&prefectureCode=13&cityCode=13113&categoryId=7&page=2&size=20',
    )
  })

  it('未指定条件ではクエリ文字列を付けない', async () => {
    await useRecruitmentCrud().listMyMarketListings()

    expect(mockApi).toHaveBeenCalledWith('/api/v1/me/market/listings')
  })

  it('作成・編集・取消を本人専用APIへ送る', async () => {
    const createBody = { title: '個人札' }
    const updateBody = { title: '更新後' }

    await useRecruitmentCrud().createMyMarketListing(createBody as never)
    await useRecruitmentCrud().updateMyMarketListing(42, updateBody)
    await useRecruitmentCrud().cancelMyMarketListing(42, { reason: '中止' })

    expect(mockApi).toHaveBeenNthCalledWith(1, '/api/v1/me/market/listings', {
      method: 'POST',
      body: createBody,
    })
    expect(mockApi).toHaveBeenNthCalledWith(2, '/api/v1/me/market/listings/42', {
      method: 'PATCH',
      body: updateBody,
    })
    expect(mockApi).toHaveBeenNthCalledWith(3, '/api/v1/me/market/listings/42/cancel', {
      method: 'POST',
      body: { reason: '中止' },
    })
  })

  it('本人札のマッチングをページング取得する', async () => {
    await useRecruitmentCrud().listMyMarketMatches(42, { page: 1, size: 10 })

    expect(mockApi).toHaveBeenCalledWith('/api/v1/me/market/listings/42/matches?page=1&size=10')
  })
})
