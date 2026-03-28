import type { DwellingUnit, ResidentResponse, PropertyListing } from '~/types/resident'

export function useResidentApi() {
  const api = useApi()
  const b = (teamId: number) => `/api/v1/teams/${teamId}`

  async function getUnits(teamId: number, params?: Record<string, unknown>) {
    const q = new URLSearchParams(); if (params) for (const [k, v] of Object.entries(params)) { if (v != null) q.set(k, String(v)) }
    return api<{ data: DwellingUnit[] }>(`${b(teamId)}/dwelling-units?${q}`)
  }
  async function getUnit(teamId: number, unitId: number) { return api<{ data: DwellingUnit }>(`${b(teamId)}/dwelling-units/${unitId}`) }
  async function createUnit(teamId: number, body: Record<string, unknown>) { return api<{ data: DwellingUnit }>(`${b(teamId)}/dwelling-units`, { method: 'POST', body }) }
  async function updateUnit(teamId: number, unitId: number, body: Record<string, unknown>) { return api(`${b(teamId)}/dwelling-units/${unitId}`, { method: 'PUT', body }) }
  async function deleteUnit(teamId: number, unitId: number) { return api(`${b(teamId)}/dwelling-units/${unitId}`, { method: 'DELETE' }) }
  async function addResident(teamId: number, unitId: number, body: Record<string, unknown>) { return api(`${b(teamId)}/dwelling-units/${unitId}/residents`, { method: 'POST', body }) }
  async function moveOutResident(teamId: number, unitId: number, residentId: number) { return api(`${b(teamId)}/dwelling-units/${unitId}/residents/${residentId}/move-out`, { method: 'PATCH' }) }
  async function getListings(teamId: number) { return api<{ data: PropertyListing[] }>(`${b(teamId)}/property-listings`) }
  async function createListing(teamId: number, body: Record<string, unknown>) { return api(`${b(teamId)}/property-listings`, { method: 'POST', body }) }

  return { getUnits, getUnit, createUnit, updateUnit, deleteUnit, addResident, moveOutResident, getListings, createListing }
}
