import { describe, expect, it } from 'vitest'
import { getScopeMarketRoutes } from '~/utils/scopeMarket'

describe('getScopeMarketRoutes', () => {
  it.each([
    [
      'team',
      'alpha',
      '/teams/alpha/market',
      '/teams/alpha/recruitment-listings/new',
      '/teams/alpha/recruitment-listings',
    ],
    [
      'organization',
      'city-hall',
      '/organizations/city-hall/market',
      '/organizations/city-hall/recruitment-listings/new',
      '/organizations/city-hall/recruitment-listings',
    ],
  ] as const)('%s の市導線は現在の主体を保持する', (scopeType, scopeId, hub, create, history) => {
    expect(getScopeMarketRoutes(scopeType, scopeId)).toEqual({ hub, create, history })
  })
})
