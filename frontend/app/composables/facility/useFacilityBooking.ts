import type {
  FacilityBookingResponse,
  BookingDetailResponse,
  BookingPaymentResponse,
  CalendarBookingResponse,
} from '~/types/facility'

/**
 * 施設予約（Booking）の API ラッパー。
 *
 * リファクタリング第 12 弾で useFacilityApi から分離した。
 * 公開関数の名前・シグネチャは分割前と完全に同一を保つ。
 */
export function useFacilityBooking() {
  const api = useApi()

  function buildBase(scopeType: 'team' | 'organization', scopeId: string) {
    return scopeType === 'team' ? `/api/v1/teams/${scopeId}` : `/api/v1/organizations/${scopeId}`
  }

  // === Bookings ===
  async function getBookings(
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
    return api<{ data: FacilityBookingResponse[] }>(
      `${buildBase(scopeType, scopeId)}/facilities/bookings${qs ? `?${qs}` : ''}`,
    )
  }

  async function createBooking(
    scopeType: 'team' | 'organization',
    scopeId: string,
    body: Record<string, unknown>,
  ) {
    return api<{ data: FacilityBookingResponse }>(
      `${buildBase(scopeType, scopeId)}/facilities/bookings`,
      { method: 'POST', body },
    )
  }

  async function getBookingCalendar(
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
    return api<{ data: CalendarBookingResponse[] }>(
      `${buildBase(scopeType, scopeId)}/facilities/bookings/calendar${qs ? `?${qs}` : ''}`,
    )
  }

  async function getBooking(
    scopeType: 'team' | 'organization',
    scopeId: string,
    bookingId: number,
  ) {
    return api<{ data: BookingDetailResponse }>(
      `${buildBase(scopeType, scopeId)}/facilities/bookings/${bookingId}`,
    )
  }

  async function updateBooking(
    scopeType: 'team' | 'organization',
    scopeId: string,
    bookingId: number,
    body: Record<string, unknown>,
  ) {
    return api<{ data: FacilityBookingResponse }>(
      `${buildBase(scopeType, scopeId)}/facilities/bookings/${bookingId}`,
      { method: 'PATCH', body },
    )
  }

  async function deleteBooking(
    scopeType: 'team' | 'organization',
    scopeId: string,
    bookingId: number,
  ) {
    return api(`${buildBase(scopeType, scopeId)}/facilities/bookings/${bookingId}`, {
      method: 'DELETE',
    })
  }

  async function approveBooking(
    scopeType: 'team' | 'organization',
    scopeId: string,
    bookingId: number,
    body?: Record<string, unknown>,
  ) {
    return api(`${buildBase(scopeType, scopeId)}/facilities/bookings/${bookingId}/approve`, {
      method: 'PATCH',
      body,
    })
  }

  async function rejectBooking(
    scopeType: 'team' | 'organization',
    scopeId: string,
    bookingId: number,
    body?: Record<string, unknown>,
  ) {
    return api(`${buildBase(scopeType, scopeId)}/facilities/bookings/${bookingId}/reject`, {
      method: 'PATCH',
      body,
    })
  }

  async function checkInBooking(
    scopeType: 'team' | 'organization',
    scopeId: string,
    bookingId: number,
  ) {
    return api(`${buildBase(scopeType, scopeId)}/facilities/bookings/${bookingId}/check-in`, {
      method: 'PATCH',
    })
  }

  async function completeBooking(
    scopeType: 'team' | 'organization',
    scopeId: string,
    bookingId: number,
  ) {
    return api(`${buildBase(scopeType, scopeId)}/facilities/bookings/${bookingId}/complete`, {
      method: 'PATCH',
    })
  }

  async function getBookingConfirmationPdf(
    scopeType: 'team' | 'organization',
    scopeId: string,
    bookingId: number,
  ) {
    return api(`${buildBase(scopeType, scopeId)}/facilities/bookings/${bookingId}/confirmation-pdf`)
  }

  async function getBookingPayment(
    scopeType: 'team' | 'organization',
    scopeId: string,
    bookingId: number,
  ) {
    return api<{ data: BookingPaymentResponse }>(
      `${buildBase(scopeType, scopeId)}/facilities/bookings/${bookingId}/payment`,
    )
  }

  async function confirmBookingPayment(
    scopeType: 'team' | 'organization',
    scopeId: string,
    bookingId: number,
    body?: Record<string, unknown>,
  ) {
    return api(
      `${buildBase(scopeType, scopeId)}/facilities/bookings/${bookingId}/payment/confirm`,
      { method: 'PATCH', body },
    )
  }

  return {
    // Bookings
    getBookings,
    createBooking,
    getBookingCalendar,
    getBooking,
    updateBooking,
    deleteBooking,
    approveBooking,
    rejectBooking,
    checkInBooking,
    completeBooking,
    getBookingConfirmationPdf,
    getBookingPayment,
    confirmBookingPayment,
  }
}
