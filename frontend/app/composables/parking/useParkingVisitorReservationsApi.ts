import type { VisitorReservationResponse } from '~/types/parking'
import { buildBase } from './useParkingApiBase'

export function useParkingVisitorReservationsApi() {
  const api = useApi()

  async function getVisitorReservations(
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
    return api<{ data: VisitorReservationResponse[] }>(
      `${buildBase(scopeType, scopeId)}/parking/visitor-reservations${qs ? `?${qs}` : ''}`,
    )
  }

  async function createVisitorReservation(
    scopeType: 'team' | 'organization',
    scopeId: string,
    body: Record<string, unknown>,
  ) {
    return api<{ data: VisitorReservationResponse }>(
      `${buildBase(scopeType, scopeId)}/parking/visitor-reservations`,
      { method: 'POST', body },
    )
  }

  async function getVisitorReservationAvailability(
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
    return api(
      `${buildBase(scopeType, scopeId)}/parking/visitor-reservations/availability${qs ? `?${qs}` : ''}`,
    )
  }

  async function getVisitorReservation(
    scopeType: 'team' | 'organization',
    scopeId: string,
    reservationId: number,
  ) {
    return api<{ data: VisitorReservationResponse }>(
      `${buildBase(scopeType, scopeId)}/parking/visitor-reservations/${reservationId}`,
    )
  }

  async function deleteVisitorReservation(
    scopeType: 'team' | 'organization',
    scopeId: string,
    reservationId: number,
  ) {
    return api(`${buildBase(scopeType, scopeId)}/parking/visitor-reservations/${reservationId}`, {
      method: 'DELETE',
    })
  }

  async function approveVisitorReservation(
    scopeType: 'team' | 'organization',
    scopeId: string,
    reservationId: number,
    body?: Record<string, unknown>,
  ) {
    return api(
      `${buildBase(scopeType, scopeId)}/parking/visitor-reservations/${reservationId}/approve`,
      { method: 'PATCH', body },
    )
  }

  async function rejectVisitorReservation(
    scopeType: 'team' | 'organization',
    scopeId: string,
    reservationId: number,
    body?: Record<string, unknown>,
  ) {
    return api(
      `${buildBase(scopeType, scopeId)}/parking/visitor-reservations/${reservationId}/reject`,
      { method: 'PATCH', body },
    )
  }

  async function checkInVisitorReservation(
    scopeType: 'team' | 'organization',
    scopeId: string,
    reservationId: number,
  ) {
    return api(
      `${buildBase(scopeType, scopeId)}/parking/visitor-reservations/${reservationId}/check-in`,
      { method: 'PATCH' },
    )
  }

  async function completeVisitorReservation(
    scopeType: 'team' | 'organization',
    scopeId: string,
    reservationId: number,
  ) {
    return api(
      `${buildBase(scopeType, scopeId)}/parking/visitor-reservations/${reservationId}/complete`,
      { method: 'PATCH' },
    )
  }

  return {
    getVisitorReservations,
    createVisitorReservation,
    getVisitorReservationAvailability,
    getVisitorReservation,
    deleteVisitorReservation,
    approveVisitorReservation,
    rejectVisitorReservation,
    checkInVisitorReservation,
    completeVisitorReservation,
  }
}
