import type { DwellingUnit, ResidentResponse, PropertyListing } from '~/types/resident'

export function useResidentApi() {
  const api = useApi()

  function buildBase(scopeType: 'team' | 'organization', scopeId: number) {
    return scopeType === 'team' ? `/api/v1/teams/${scopeId}` : `/api/v1/organizations/${scopeId}`
  }

  // === Dwelling Units ===
  async function getUnits(
    scopeType: 'team' | 'organization',
    scopeId: number,
    params?: Record<string, unknown>,
  ) {
    const q = new URLSearchParams()
    if (params)
      for (const [k, v] of Object.entries(params)) {
        if (v != null) q.set(k, String(v))
      }
    return api<{ data: DwellingUnit[] }>(`${buildBase(scopeType, scopeId)}/dwelling-units?${q}`)
  }

  async function getUnit(scopeType: 'team' | 'organization', scopeId: number, unitId: number) {
    return api<{ data: DwellingUnit }>(`${buildBase(scopeType, scopeId)}/dwelling-units/${unitId}`)
  }

  async function createUnit(
    scopeType: 'team' | 'organization',
    scopeId: number,
    body: Record<string, unknown>,
  ) {
    return api<{ data: DwellingUnit }>(`${buildBase(scopeType, scopeId)}/dwelling-units`, {
      method: 'POST',
      body,
    })
  }

  async function updateUnit(
    scopeType: 'team' | 'organization',
    scopeId: number,
    unitId: number,
    body: Record<string, unknown>,
  ) {
    return api(`${buildBase(scopeType, scopeId)}/dwelling-units/${unitId}`, { method: 'PUT', body })
  }

  async function deleteUnit(scopeType: 'team' | 'organization', scopeId: number, unitId: number) {
    return api(`${buildBase(scopeType, scopeId)}/dwelling-units/${unitId}`, { method: 'DELETE' })
  }

  // === Residents ===
  async function getResidents(scopeType: 'team' | 'organization', scopeId: number, unitId: number) {
    return api<{ data: ResidentResponse[] }>(
      `${buildBase(scopeType, scopeId)}/dwelling-units/${unitId}/residents`,
    )
  }

  async function addResident(
    scopeType: 'team' | 'organization',
    scopeId: number,
    unitId: number,
    body: Record<string, unknown>,
  ) {
    return api(`${buildBase(scopeType, scopeId)}/dwelling-units/${unitId}/residents`, {
      method: 'POST',
      body,
    })
  }

  async function updateResident(
    scopeType: 'team' | 'organization',
    scopeId: number,
    residentId: number,
    body: Record<string, unknown>,
  ) {
    return api(`${buildBase(scopeType, scopeId)}/residents/${residentId}`, { method: 'PUT', body })
  }

  async function deleteResident(
    scopeType: 'team' | 'organization',
    scopeId: number,
    residentId: number,
  ) {
    return api(`${buildBase(scopeType, scopeId)}/residents/${residentId}`, { method: 'DELETE' })
  }

  async function moveOutResident(
    scopeType: 'team' | 'organization',
    scopeId: number,
    residentId: number,
  ) {
    return api(`${buildBase(scopeType, scopeId)}/residents/${residentId}/move-out`, {
      method: 'PATCH',
    })
  }

  async function verifyResident(
    scopeType: 'team' | 'organization',
    scopeId: number,
    residentId: number,
  ) {
    return api(`${buildBase(scopeType, scopeId)}/residents/${residentId}/verify`, {
      method: 'PATCH',
    })
  }

  // === Documents ===
  async function getDocuments(
    scopeType: 'team' | 'organization',
    scopeId: number,
    residentId: number,
  ) {
    return api(`${buildBase(scopeType, scopeId)}/residents/${residentId}/documents`)
  }

  async function addDocument(
    scopeType: 'team' | 'organization',
    scopeId: number,
    residentId: number,
    body: FormData | Record<string, unknown>,
  ) {
    return api(`${buildBase(scopeType, scopeId)}/residents/${residentId}/documents`, {
      method: 'POST',
      body,
    })
  }

  async function deleteDocument(
    scopeType: 'team' | 'organization',
    scopeId: number,
    residentId: number,
    docId: number,
  ) {
    return api(`${buildBase(scopeType, scopeId)}/residents/${residentId}/documents/${docId}`, {
      method: 'DELETE',
    })
  }

  // === My Resident Info ===
  async function getMyResidentInfo() {
    return api('/api/v1/users/me/resident-info')
  }

  // === Property Listings ===
  async function getListings(teamId: number) {
    return api<{ data: PropertyListing[] }>(`/api/v1/teams/${teamId}/property-listings`)
  }
  async function createListing(teamId: number, body: Record<string, unknown>) {
    return api(`/api/v1/teams/${teamId}/property-listings`, { method: 'POST', body })
  }

  return {
    getUnits,
    getUnit,
    createUnit,
    updateUnit,
    deleteUnit,
    getResidents,
    addResident,
    updateResident,
    deleteResident,
    moveOutResident,
    verifyResident,
    getDocuments,
    addDocument,
    deleteDocument,
    getMyResidentInfo,
    getListings,
    createListing,
  }
}
