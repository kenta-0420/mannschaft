import type { ListingResponse, ListingDetailResponse } from '~/types/parking'
import { buildBase } from './useParkingApiBase'

export function useParkingListingsApi() {
  const api = useApi()

  async function getListings(
    scopeType: 'team' | 'organization',
    scopeId: string,
    params?: Record<string, unknown>,
  ) {
    const q = new URLSearchParams()
    if (params)
      for (const [k, v] of Object.entries(params)) {
        if (v != null) q.set(k, String(v))
      }
    const qs = q.toString()
    return api<{ data: ListingResponse[] }>(
      `${buildBase(scopeType, scopeId)}/parking/listings${qs ? `?${qs}` : ''}`,
    )
  }

  async function createListing(
    scopeType: 'team' | 'organization',
    scopeId: string,
    body: Record<string, unknown>,
  ) {
    return api<{ data: ListingResponse }>(`${buildBase(scopeType, scopeId)}/parking/listings`, {
      method: 'POST',
      body,
    })
  }

  async function getListing(
    scopeType: 'team' | 'organization',
    scopeId: string,
    listingId: number,
  ) {
    return api<{ data: ListingDetailResponse }>(
      `${buildBase(scopeType, scopeId)}/parking/listings/${listingId}`,
    )
  }

  async function updateListing(
    scopeType: 'team' | 'organization',
    scopeId: string,
    listingId: number,
    body: Record<string, unknown>,
  ) {
    return api<{ data: ListingResponse }>(
      `${buildBase(scopeType, scopeId)}/parking/listings/${listingId}`,
      { method: 'PUT', body },
    )
  }

  async function deleteListing(
    scopeType: 'team' | 'organization',
    scopeId: string,
    listingId: number,
  ) {
    return api(`${buildBase(scopeType, scopeId)}/parking/listings/${listingId}`, {
      method: 'DELETE',
    })
  }

  async function applyToListing(
    scopeType: 'team' | 'organization',
    scopeId: string,
    listingId: number,
    body: Record<string, unknown>,
  ) {
    return api(`${buildBase(scopeType, scopeId)}/parking/listings/${listingId}/apply`, {
      method: 'POST',
      body,
    })
  }

  async function transferListing(
    scopeType: 'team' | 'organization',
    scopeId: string,
    listingId: number,
    body: Record<string, unknown>,
  ) {
    return api(`${buildBase(scopeType, scopeId)}/parking/listings/${listingId}/transfer`, {
      method: 'PATCH',
      body,
    })
  }

  return {
    getListings,
    createListing,
    getListing,
    updateListing,
    deleteListing,
    applyToListing,
    transferListing,
  }
}
