import type { WatchlistResponse } from '~/types/parking'
import { buildBase } from './useParkingApiBase'

export function useParkingWatchlistApi() {
  const api = useApi()

  async function getWatchlist(scopeType: 'team' | 'organization', scopeId: number) {
    return api<{ data: WatchlistResponse[] }>(`${buildBase(scopeType, scopeId)}/parking/watchlist`)
  }

  async function addToWatchlist(
    scopeType: 'team' | 'organization',
    scopeId: number,
    body: Record<string, unknown>,
  ) {
    return api<{ data: WatchlistResponse }>(`${buildBase(scopeType, scopeId)}/parking/watchlist`, {
      method: 'POST',
      body,
    })
  }

  async function removeFromWatchlist(
    scopeType: 'team' | 'organization',
    scopeId: number,
    watchlistId: number,
  ) {
    return api(`${buildBase(scopeType, scopeId)}/parking/watchlist/${watchlistId}`, {
      method: 'DELETE',
    })
  }

  return {
    getWatchlist,
    addToWatchlist,
    removeFromWatchlist,
  }
}
