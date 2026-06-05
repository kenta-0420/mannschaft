import type { ParkingSpaceResponse } from '~/types/parking'
import { buildBase } from './useParkingApiBase'

export function useParkingSpacesApi() {
  const api = useApi()

  async function getSpaces(scopeType: 'team' | 'organization', scopeId: string) {
    return api<{ data: ParkingSpaceResponse[] }>(`${buildBase(scopeType, scopeId)}/parking/spaces`)
  }

  async function getSpace(scopeType: 'team' | 'organization', scopeId: string, spaceId: number) {
    return api<{ data: ParkingSpaceResponse }>(
      `${buildBase(scopeType, scopeId)}/parking/spaces/${spaceId}`,
    )
  }

  async function createSpace(
    scopeType: 'team' | 'organization',
    scopeId: string,
    body: Record<string, unknown>,
  ) {
    return api<{ data: ParkingSpaceResponse }>(`${buildBase(scopeType, scopeId)}/parking/spaces`, {
      method: 'POST',
      body,
    })
  }

  async function updateSpace(
    scopeType: 'team' | 'organization',
    scopeId: string,
    spaceId: number,
    body: Record<string, unknown>,
  ) {
    return api<{ data: ParkingSpaceResponse }>(
      `${buildBase(scopeType, scopeId)}/parking/spaces/${spaceId}`,
      { method: 'PUT', body },
    )
  }

  async function deleteSpace(scopeType: 'team' | 'organization', scopeId: string, spaceId: number) {
    return api(`${buildBase(scopeType, scopeId)}/parking/spaces/${spaceId}`, { method: 'DELETE' })
  }

  async function bulkCreateSpaces(
    scopeType: 'team' | 'organization',
    scopeId: string,
    body: Record<string, unknown>,
  ) {
    return api(`${buildBase(scopeType, scopeId)}/parking/spaces/bulk-create`, {
      method: 'POST',
      body,
    })
  }

  async function bulkAssignSpaces(
    scopeType: 'team' | 'organization',
    scopeId: string,
    body: Record<string, unknown>,
  ) {
    return api(`${buildBase(scopeType, scopeId)}/parking/spaces/bulk-assign`, {
      method: 'POST',
      body,
    })
  }

  async function swapSpaces(
    scopeType: 'team' | 'organization',
    scopeId: string,
    body: Record<string, unknown>,
  ) {
    return api(`${buildBase(scopeType, scopeId)}/parking/spaces/swap`, { method: 'POST', body })
  }

  async function getVacantSpaces(scopeType: 'team' | 'organization', scopeId: string) {
    return api<{ data: ParkingSpaceResponse[] }>(
      `${buildBase(scopeType, scopeId)}/parking/spaces/vacant`,
    )
  }

  async function assignSpace(
    scopeType: 'team' | 'organization',
    scopeId: string,
    spaceId: number,
    body: Record<string, unknown>,
  ) {
    return api(`${buildBase(scopeType, scopeId)}/parking/spaces/${spaceId}/assign`, {
      method: 'POST',
      body,
    })
  }

  async function releaseSpace(
    scopeType: 'team' | 'organization',
    scopeId: string,
    spaceId: number,
  ) {
    return api(`${buildBase(scopeType, scopeId)}/parking/spaces/${spaceId}/release`, {
      method: 'POST',
    })
  }

  async function setSpaceMaintenance(
    scopeType: 'team' | 'organization',
    scopeId: string,
    spaceId: number,
    body?: Record<string, unknown>,
  ) {
    return api(`${buildBase(scopeType, scopeId)}/parking/spaces/${spaceId}/maintenance`, {
      method: 'PATCH',
      body,
    })
  }

  async function getSpaceHistory(
    scopeType: 'team' | 'organization',
    scopeId: string,
    spaceId: number,
  ) {
    return api(`${buildBase(scopeType, scopeId)}/parking/spaces/${spaceId}/history`)
  }

  async function getSpacePriceHistory(
    scopeType: 'team' | 'organization',
    scopeId: string,
    spaceId: number,
  ) {
    return api(`${buildBase(scopeType, scopeId)}/parking/spaces/${spaceId}/price-history`)
  }

  async function acceptApplicationsForSpace(
    scopeType: 'team' | 'organization',
    scopeId: string,
    spaceId: number,
    body: Record<string, unknown>,
  ) {
    return api(`${buildBase(scopeType, scopeId)}/parking/spaces/${spaceId}/accept-applications`, {
      method: 'PATCH',
      body,
    })
  }

  return {
    getSpaces,
    getSpace,
    createSpace,
    updateSpace,
    deleteSpace,
    bulkCreateSpaces,
    bulkAssignSpaces,
    swapSpaces,
    getVacantSpaces,
    assignSpace,
    releaseSpace,
    setSpaceMaintenance,
    getSpaceHistory,
    getSpacePriceHistory,
    acceptApplicationsForSpace,
  }
}
