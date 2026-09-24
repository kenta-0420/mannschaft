import { beforeEach, describe, expect, it, vi } from 'vitest'

const mockApi = vi.fn()

vi.mock('~/composables/useApi', () => ({
  useApi: () => mockApi,
}))

const { useMarketApi } = await import('./useMarketApi')

describe('useMarketApi', () => {
  beforeEach(() => {
    mockApi.mockReset()
  })

  it('札主区分を owner_type として一覧 API へ送る', async () => {
    await useMarketApi().listMarketListings({ ownerType: 'ORGANIZATION' })

    expect(mockApi).toHaveBeenCalledWith(
      '/api/v1/public/market/listings?owner_type=ORGANIZATION',
    )
  })

  it('地域除外・締切順・ページングを一覧 API の契約名で送る', async () => {
    await useMarketApi().listMarketListings({
      prefecture: '13',
      includeRegionNone: false,
      sort: 'DEADLINE_ASC',
      page: 2,
      size: 20,
    })

    expect(mockApi).toHaveBeenCalledWith(
      '/api/v1/public/market/listings?prefecture=13&include_region_none=false&sort=DEADLINE_ASC&page=2&size=20',
    )
  })
})
