import type { FacilityResponse, FacilityBookingResponse } from '~/types/facility'

export function useFacilityApi() {
  const api = useApi()
  const b = (teamId: number) => `/api/v1/teams/${teamId}`

  async function getFacilities(teamId: number) { return api<{ data: FacilityResponse[] }>(`${b(teamId)}/facilities`) }
  async function getFacility(teamId: number, facilityId: number) { return api<{ data: FacilityResponse }>(`${b(teamId)}/facilities/${facilityId}`) }
  async function createFacility(teamId: number, body: Record<string, unknown>) { return api<{ data: FacilityResponse }>(`${b(teamId)}/facilities`, { method: 'POST', body }) }
  async function updateFacility(teamId: number, facilityId: number, body: Record<string, unknown>) { return api(`${b(teamId)}/facilities/${facilityId}`, { method: 'PUT', body }) }
  async function deleteFacility(teamId: number, facilityId: number) { return api(`${b(teamId)}/facilities/${facilityId}`, { method: 'DELETE' }) }
  async function getBookings(teamId: number, facilityId?: number, params?: Record<string, unknown>) {
    const q = new URLSearchParams(); if (facilityId) q.set('facility_id', String(facilityId))
    if (params) for (const [k, v] of Object.entries(params)) { if (v != null) q.set(k, String(v)) }
    return api<{ data: FacilityBookingResponse[] }>(`${b(teamId)}/facilities/bookings?${q}`)
  }
  async function createBooking(teamId: number, body: Record<string, unknown>) { return api<{ data: FacilityBookingResponse }>(`${b(teamId)}/facilities/bookings`, { method: 'POST', body }) }
  async function approveBooking(teamId: number, bookingId: number) { return api(`${b(teamId)}/facilities/bookings/${bookingId}/approve`, { method: 'PATCH' }) }
  async function rejectBooking(teamId: number, bookingId: number, reason?: string) { return api(`${b(teamId)}/facilities/bookings/${bookingId}/reject`, { method: 'PATCH', body: { reason } }) }
  async function checkIn(teamId: number, bookingId: number) { return api(`${b(teamId)}/facilities/bookings/${bookingId}/check-in`, { method: 'PATCH' }) }

  return { getFacilities, getFacility, createFacility, updateFacility, deleteFacility, getBookings, createBooking, approveBooking, rejectBooking, checkIn }
}
