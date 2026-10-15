export type MarketScopeType = 'team' | 'organization'

export interface ScopeMarketRoutes {
  hub: string
  create: string
  history: string
}

/** 現在の主体を保持した管理市の導線を組み立てる。 */
export function getScopeMarketRoutes(
  scopeType: MarketScopeType,
  scopeId: string,
): ScopeMarketRoutes {
  const base = scopeType === 'team' ? `/teams/${scopeId}` : `/organizations/${scopeId}`

  return {
    hub: `${base}/market`,
    create: `${base}/recruitment-listings/new`,
    history: `${base}/recruitment-listings`,
  }
}
