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
})
