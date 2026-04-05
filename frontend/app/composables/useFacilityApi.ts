import type {
  FacilityResponse,
  FacilityBookingResponse,
  FacilityDetailResponse,
  FacilitySettingsResponse,
  FacilityStatsResponse,
  BookingDetailResponse,
  BookingPaymentResponse,
  CalendarBookingResponse,
  FacilityEquipmentResponse,
  TimeRateResponse,
  UsageRuleResponse,
} from '~/types/facility'

export function useFacilityApi() {
  const api = useApi()

  function buildBase(scopeType: 'team' | 'organization', scopeId: number) {
    return scopeType === 'team' ? `/api/v1/teams/${scopeId}` : `/api/v1/organizations/${scopeId}`
  }

  // === Facilities CRUD ===
  async function getFacilities(
    scopeType: 'team' | 'organization',
    scopeId: number,
    params?: Record<string, unknown>,
  ) {
    const q = new URLSearchParams()
    if (params)
      for (const [k, v] of Object.entries(params)) {
        if (v != null) q.set(k, String(v))
      }
    const qs = q.toString()
    return api<{ data: FacilityResponse[] }>(
      `${buildBase(scopeType, scopeId)}/facilities${qs ? `?${qs}` : ''}`,
    )
  }

  async function createFacility(
    scopeType: 'team' | 'organization',
    scopeId: number,
    body: Record<string, unknown>,
  ) {
    return api<{ data: FacilityResponse }>(`${buildBase(scopeType, scopeId)}/facilities`, {
      method: 'POST',
      body,
    })
  }

  async function bulkCreateFacilities(
    scopeType: 'team' | 'organization',
    scopeId: number,
    body: Record<string, unknown>,
  ) {
    return api(`${buildBase(scopeType, scopeId)}/facilities/bulk-create`, { method: 'POST', body })
  }

  async function getFacility(
    scopeType: 'team' | 'organization',
    scopeId: number,
    facilityId: number,
  ) {
    return api<{ data: FacilityDetailResponse }>(
      `${buildBase(scopeType, scopeId)}/facilities/${facilityId}`,
    )
  }

  async function updateFacility(
    scopeType: 'team' | 'organization',
    scopeId: number,
    facilityId: number,
    body: Record<string, unknown>,
  ) {
    return api<{ data: FacilityResponse }>(
      `${buildBase(scopeType, scopeId)}/facilities/${facilityId}`,
      { method: 'PUT', body },
    )
  }

  async function deleteFacility(
    scopeType: 'team' | 'organization',
    scopeId: number,
    facilityId: number,
  ) {
    return api(`${buildBase(scopeType, scopeId)}/facilities/${facilityId}`, { method: 'DELETE' })
  }

  // === Facility Availability ===
  async function getFacilityAvailability(
    scopeType: 'team' | 'organization',
    scopeId: number,
    facilityId: number,
    params?: Record<string, unknown>,
  ) {
    const q = new URLSearchParams()
    if (params)
      for (const [k, v] of Object.entries(params)) {
        if (v != null) q.set(k, String(v))
      }
    const qs = q.toString()
    return api(
      `${buildBase(scopeType, scopeId)}/facilities/${facilityId}/availability${qs ? `?${qs}` : ''}`,
    )
  }

  // === Facility Rates ===
  async function getFacilityRates(
    scopeType: 'team' | 'organization',
    scopeId: number,
    facilityId: number,
  ) {
    return api<{ data: TimeRateResponse[] }>(
      `${buildBase(scopeType, scopeId)}/facilities/${facilityId}/rates`,
    )
  }

  async function updateFacilityRates(
    scopeType: 'team' | 'organization',
    scopeId: number,
    facilityId: number,
    body: Record<string, unknown>,
  ) {
    return api(`${buildBase(scopeType, scopeId)}/facilities/${facilityId}/rates`, {
      method: 'PUT',
      body,
    })
  }

  // === Facility Rules ===
  async function getFacilityRules(
    scopeType: 'team' | 'organization',
    scopeId: number,
    facilityId: number,
  ) {
    return api<{ data: UsageRuleResponse }>(
      `${buildBase(scopeType, scopeId)}/facilities/${facilityId}/rules`,
    )
  }

  async function updateFacilityRules(
    scopeType: 'team' | 'organization',
    scopeId: number,
    facilityId: number,
    body: Record<string, unknown>,
  ) {
    return api<{ data: UsageRuleResponse }>(
      `${buildBase(scopeType, scopeId)}/facilities/${facilityId}/rules`,
      { method: 'PUT', body },
    )
  }

  // === Facility Equipment ===
  async function getEquipment(
    scopeType: 'team' | 'organization',
    scopeId: number,
    facilityId: number,
  ) {
    return api<{ data: FacilityEquipmentResponse[] }>(
      `${buildBase(scopeType, scopeId)}/facilities/${facilityId}/equipment`,
    )
  }

  async function createEquipment(
    scopeType: 'team' | 'organization',
    scopeId: number,
    facilityId: number,
    body: Record<string, unknown>,
  ) {
    return api<{ data: FacilityEquipmentResponse }>(
      `${buildBase(scopeType, scopeId)}/facilities/${facilityId}/equipment`,
      { method: 'POST', body },
    )
  }

  async function updateEquipment(
    scopeType: 'team' | 'organization',
    scopeId: number,
    facilityId: number,
    equipmentId: number,
    body: Record<string, unknown>,
  ) {
    return api<{ data: FacilityEquipmentResponse }>(
      `${buildBase(scopeType, scopeId)}/facilities/${facilityId}/equipment/${equipmentId}`,
      { method: 'PUT', body },
    )
  }

  async function deleteEquipment(
    scopeType: 'team' | 'organization',
    scopeId: number,
    facilityId: number,
    equipmentId: number,
  ) {
    return api(
      `${buildBase(scopeType, scopeId)}/facilities/${facilityId}/equipment/${equipmentId}`,
      { method: 'DELETE' },
    )
  }

  // === Bookings ===
  async function getBookings(
    scopeType: 'team' | 'organization',
    scopeId: number,
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
    scopeId: number,
    body: Record<string, unknown>,
  ) {
    return api<{ data: FacilityBookingResponse }>(
      `${buildBase(scopeType, scopeId)}/facilities/bookings`,
      { method: 'POST', body },
    )
  }

  async function getBookingCalendar(
    scopeType: 'team' | 'organization',
    scopeId: number,
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
    scopeId: number,
    bookingId: number,
  ) {
    return api<{ data: BookingDetailResponse }>(
      `${buildBase(scopeType, scopeId)}/facilities/bookings/${bookingId}`,
    )
  }

  async function updateBooking(
    scopeType: 'team' | 'organization',
    scopeId: number,
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
    scopeId: number,
    bookingId: number,
  ) {
    return api(`${buildBase(scopeType, scopeId)}/facilities/bookings/${bookingId}`, {
      method: 'DELETE',
    })
  }

  async function approveBooking(
    scopeType: 'team' | 'organization',
    scopeId: number,
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
    scopeId: number,
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
    scopeId: number,
    bookingId: number,
  ) {
    return api(`${buildBase(scopeType, scopeId)}/facilities/bookings/${bookingId}/check-in`, {
      method: 'PATCH',
    })
  }

  async function completeBooking(
    scopeType: 'team' | 'organization',
    scopeId: number,
    bookingId: number,
  ) {
    return api(`${buildBase(scopeType, scopeId)}/facilities/bookings/${bookingId}/complete`, {
      method: 'PATCH',
    })
  }

  async function getBookingConfirmationPdf(
    scopeType: 'team' | 'organization',
    scopeId: number,
    bookingId: number,
  ) {
    return api(`${buildBase(scopeType, scopeId)}/facilities/bookings/${bookingId}/confirmation-pdf`)
  }

  async function getBookingPayment(
    scopeType: 'team' | 'organization',
    scopeId: number,
    bookingId: number,
  ) {
    return api<{ data: BookingPaymentResponse }>(
      `${buildBase(scopeType, scopeId)}/facilities/bookings/${bookingId}/payment`,
    )
  }

  async function confirmBookingPayment(
    scopeType: 'team' | 'organization',
    scopeId: number,
    bookingId: number,
    body?: Record<string, unknown>,
  ) {
    return api(
      `${buildBase(scopeType, scopeId)}/facilities/bookings/${bookingId}/payment/confirm`,
      { method: 'PATCH', body },
    )
  }

  // === Settings ===
  async function getFacilitySettings(scopeType: 'team' | 'organization', scopeId: number) {
    return api<{ data: FacilitySettingsResponse }>(
      `${buildBase(scopeType, scopeId)}/facilities/settings`,
    )
  }

  async function updateFacilitySettings(
    scopeType: 'team' | 'organization',
    scopeId: number,
    body: Record<string, unknown>,
  ) {
    return api<{ data: FacilitySettingsResponse }>(
      `${buildBase(scopeType, scopeId)}/facilities/settings`,
      { method: 'PUT', body },
    )
  }

  // === Stats ===
  async function getFacilityStats(scopeType: 'team' | 'organization', scopeId: number) {
    return api<{ data: FacilityStatsResponse }>(`${buildBase(scopeType, scopeId)}/facilities/stats`)
  }

  return {
    // Facilities CRUD
    getFacilities,
    createFacility,
    bulkCreateFacilities,
    getFacility,
    updateFacility,
    deleteFacility,
    // Availability
    getFacilityAvailability,
    // Rates
    getFacilityRates,
    updateFacilityRates,
    // Rules
    getFacilityRules,
    updateFacilityRules,
    // Equipment
    getEquipment,
    createEquipment,
    updateEquipment,
    deleteEquipment,
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
    // Settings & Stats
    getFacilitySettings,
    updateFacilitySettings,
    getFacilityStats,
  }
}
