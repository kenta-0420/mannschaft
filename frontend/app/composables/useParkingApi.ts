import type { ParkingSpaceResponse, VehicleResponse, ParkingListing } from '~/types/parking'

export function useParkingApi() {
  const api = useApi()
  const b = (teamId: number) => `/api/v1/teams/${teamId}`

  async function getSpaces(teamId: number) { return api<{ data: ParkingSpaceResponse[] }>(`${b(teamId)}/parking/spaces`) }
  async function createSpace(teamId: number, body: Record<string, unknown>) { return api(`${b(teamId)}/parking/spaces`, { method: 'POST', body }) }
  async function assignSpace(teamId: number, spaceId: number, userId: number, vehicleId: number) { return api(`${b(teamId)}/parking/spaces/${spaceId}/assign`, { method: 'POST', body: { userId, vehicleId } }) }
  async function releaseSpace(teamId: number, spaceId: number) { return api(`${b(teamId)}/parking/spaces/${spaceId}/release`, { method: 'PATCH' }) }
  async function getMyVehicles() { return api<{ data: VehicleResponse[] }>('/api/v1/users/me/vehicles') }
  async function addVehicle(body: Record<string, unknown>) { return api('/api/v1/users/me/vehicles', { method: 'POST', body }) }
  async function getListings(teamId: number) { return api<{ data: ParkingListing[] }>(`${b(teamId)}/parking/listings`) }

  return { getSpaces, createSpace, assignSpace, releaseSpace, getMyVehicles, addVehicle, getListings }
}
