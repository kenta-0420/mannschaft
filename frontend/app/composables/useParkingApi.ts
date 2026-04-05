import type {
  ParkingSpaceResponse,
  VehicleResponse,
  ApplicationResponse,
  ListingResponse,
  ListingDetailResponse,
  ParkingSettingsResponse,
  ParkingStatsResponse,
  SubleaseResponse,
  SubleaseDetailResponse,
  SubleasePaymentResponse,
  SubleaseApplicationResponse,
  VisitorReservationResponse,
  VisitorRecurringResponse,
  WatchlistResponse,
} from '~/types/parking'

export function useParkingApi() {
  const api = useApi()

  function buildBase(scopeType: 'team' | 'organization', scopeId: number) {
    return scopeType === 'team' ? `/api/v1/teams/${scopeId}` : `/api/v1/organizations/${scopeId}`
  }

  // === Parking Spaces ===
  async function getSpaces(scopeType: 'team' | 'organization', scopeId: number) {
    return api<{ data: ParkingSpaceResponse[] }>(`${buildBase(scopeType, scopeId)}/parking/spaces`)
  }

  async function getSpace(scopeType: 'team' | 'organization', scopeId: number, spaceId: number) {
    return api<{ data: ParkingSpaceResponse }>(
      `${buildBase(scopeType, scopeId)}/parking/spaces/${spaceId}`,
    )
  }

  async function createSpace(
    scopeType: 'team' | 'organization',
    scopeId: number,
    body: Record<string, unknown>,
  ) {
    return api<{ data: ParkingSpaceResponse }>(`${buildBase(scopeType, scopeId)}/parking/spaces`, {
      method: 'POST',
      body,
    })
  }

  async function updateSpace(
    scopeType: 'team' | 'organization',
    scopeId: number,
    spaceId: number,
    body: Record<string, unknown>,
  ) {
    return api<{ data: ParkingSpaceResponse }>(
      `${buildBase(scopeType, scopeId)}/parking/spaces/${spaceId}`,
      { method: 'PUT', body },
    )
  }

  async function deleteSpace(scopeType: 'team' | 'organization', scopeId: number, spaceId: number) {
    return api(`${buildBase(scopeType, scopeId)}/parking/spaces/${spaceId}`, { method: 'DELETE' })
  }

  async function bulkCreateSpaces(
    scopeType: 'team' | 'organization',
    scopeId: number,
    body: Record<string, unknown>,
  ) {
    return api(`${buildBase(scopeType, scopeId)}/parking/spaces/bulk-create`, {
      method: 'POST',
      body,
    })
  }

  async function bulkAssignSpaces(
    scopeType: 'team' | 'organization',
    scopeId: number,
    body: Record<string, unknown>,
  ) {
    return api(`${buildBase(scopeType, scopeId)}/parking/spaces/bulk-assign`, {
      method: 'POST',
      body,
    })
  }

  async function swapSpaces(
    scopeType: 'team' | 'organization',
    scopeId: number,
    body: Record<string, unknown>,
  ) {
    return api(`${buildBase(scopeType, scopeId)}/parking/spaces/swap`, { method: 'POST', body })
  }

  async function getVacantSpaces(scopeType: 'team' | 'organization', scopeId: number) {
    return api<{ data: ParkingSpaceResponse[] }>(
      `${buildBase(scopeType, scopeId)}/parking/spaces/vacant`,
    )
  }

  async function assignSpace(
    scopeType: 'team' | 'organization',
    scopeId: number,
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
    scopeId: number,
    spaceId: number,
  ) {
    return api(`${buildBase(scopeType, scopeId)}/parking/spaces/${spaceId}/release`, {
      method: 'POST',
    })
  }

  async function setSpaceMaintenance(
    scopeType: 'team' | 'organization',
    scopeId: number,
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
    scopeId: number,
    spaceId: number,
  ) {
    return api(`${buildBase(scopeType, scopeId)}/parking/spaces/${spaceId}/history`)
  }

  async function getSpacePriceHistory(
    scopeType: 'team' | 'organization',
    scopeId: number,
    spaceId: number,
  ) {
    return api(`${buildBase(scopeType, scopeId)}/parking/spaces/${spaceId}/price-history`)
  }

  async function acceptApplicationsForSpace(
    scopeType: 'team' | 'organization',
    scopeId: number,
    spaceId: number,
    body: Record<string, unknown>,
  ) {
    return api(`${buildBase(scopeType, scopeId)}/parking/spaces/${spaceId}/accept-applications`, {
      method: 'PATCH',
      body,
    })
  }

  // === Applications ===
  async function getApplications(
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
    return api<{ data: ApplicationResponse[] }>(
      `${buildBase(scopeType, scopeId)}/parking/applications${qs ? `?${qs}` : ''}`,
    )
  }

  async function createApplication(
    scopeType: 'team' | 'organization',
    scopeId: number,
    body: Record<string, unknown>,
  ) {
    return api<{ data: ApplicationResponse }>(
      `${buildBase(scopeType, scopeId)}/parking/applications`,
      { method: 'POST', body },
    )
  }

  async function deleteApplication(
    scopeType: 'team' | 'organization',
    scopeId: number,
    applicationId: number,
  ) {
    return api(`${buildBase(scopeType, scopeId)}/parking/applications/${applicationId}`, {
      method: 'DELETE',
    })
  }

  async function approveApplication(
    scopeType: 'team' | 'organization',
    scopeId: number,
    applicationId: number,
  ) {
    return api(`${buildBase(scopeType, scopeId)}/parking/applications/${applicationId}/approve`, {
      method: 'PATCH',
    })
  }

  async function rejectApplication(
    scopeType: 'team' | 'organization',
    scopeId: number,
    applicationId: number,
    body?: Record<string, unknown>,
  ) {
    return api(`${buildBase(scopeType, scopeId)}/parking/applications/${applicationId}/reject`, {
      method: 'PATCH',
      body,
    })
  }

  async function runApplicationLottery(
    scopeType: 'team' | 'organization',
    scopeId: number,
    body?: Record<string, unknown>,
  ) {
    return api(`${buildBase(scopeType, scopeId)}/parking/applications/lottery`, {
      method: 'POST',
      body,
    })
  }

  // === Listings ===
  async function getListings(
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
    return api<{ data: ListingResponse[] }>(
      `${buildBase(scopeType, scopeId)}/parking/listings${qs ? `?${qs}` : ''}`,
    )
  }

  async function createListing(
    scopeType: 'team' | 'organization',
    scopeId: number,
    body: Record<string, unknown>,
  ) {
    return api<{ data: ListingResponse }>(`${buildBase(scopeType, scopeId)}/parking/listings`, {
      method: 'POST',
      body,
    })
  }

  async function getListing(
    scopeType: 'team' | 'organization',
    scopeId: number,
    listingId: number,
  ) {
    return api<{ data: ListingDetailResponse }>(
      `${buildBase(scopeType, scopeId)}/parking/listings/${listingId}`,
    )
  }

  async function updateListing(
    scopeType: 'team' | 'organization',
    scopeId: number,
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
    scopeId: number,
    listingId: number,
  ) {
    return api(`${buildBase(scopeType, scopeId)}/parking/listings/${listingId}`, {
      method: 'DELETE',
    })
  }

  async function applyToListing(
    scopeType: 'team' | 'organization',
    scopeId: number,
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
    scopeId: number,
    listingId: number,
    body: Record<string, unknown>,
  ) {
    return api(`${buildBase(scopeType, scopeId)}/parking/listings/${listingId}/transfer`, {
      method: 'PATCH',
      body,
    })
  }

  // === Settings ===
  async function getParkingSettings(scopeType: 'team' | 'organization', scopeId: number) {
    return api<{ data: ParkingSettingsResponse }>(
      `${buildBase(scopeType, scopeId)}/parking/settings`,
    )
  }

  async function updateParkingSettings(
    scopeType: 'team' | 'organization',
    scopeId: number,
    body: Record<string, unknown>,
  ) {
    return api<{ data: ParkingSettingsResponse }>(
      `${buildBase(scopeType, scopeId)}/parking/settings`,
      { method: 'PUT', body },
    )
  }

  // === Stats ===
  async function getParkingStats(scopeType: 'team' | 'organization', scopeId: number) {
    return api<{ data: ParkingStatsResponse }>(`${buildBase(scopeType, scopeId)}/parking/stats`)
  }

  // === Subleases ===
  async function getSubleases(
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
    return api<{ data: SubleaseResponse[] }>(
      `${buildBase(scopeType, scopeId)}/parking/subleases${qs ? `?${qs}` : ''}`,
    )
  }

  async function createSublease(
    scopeType: 'team' | 'organization',
    scopeId: number,
    body: Record<string, unknown>,
  ) {
    return api<{ data: SubleaseResponse }>(`${buildBase(scopeType, scopeId)}/parking/subleases`, {
      method: 'POST',
      body,
    })
  }

  async function getSublease(
    scopeType: 'team' | 'organization',
    scopeId: number,
    subleaseId: number,
  ) {
    return api<{ data: SubleaseDetailResponse }>(
      `${buildBase(scopeType, scopeId)}/parking/subleases/${subleaseId}`,
    )
  }

  async function updateSublease(
    scopeType: 'team' | 'organization',
    scopeId: number,
    subleaseId: number,
    body: Record<string, unknown>,
  ) {
    return api<{ data: SubleaseResponse }>(
      `${buildBase(scopeType, scopeId)}/parking/subleases/${subleaseId}`,
      { method: 'PUT', body },
    )
  }

  async function deleteSublease(
    scopeType: 'team' | 'organization',
    scopeId: number,
    subleaseId: number,
  ) {
    return api(`${buildBase(scopeType, scopeId)}/parking/subleases/${subleaseId}`, {
      method: 'DELETE',
    })
  }

  async function applyToSublease(
    scopeType: 'team' | 'organization',
    scopeId: number,
    subleaseId: number,
    body: Record<string, unknown>,
  ) {
    return api<{ data: SubleaseApplicationResponse }>(
      `${buildBase(scopeType, scopeId)}/parking/subleases/${subleaseId}/apply`,
      { method: 'POST', body },
    )
  }

  async function approveSublease(
    scopeType: 'team' | 'organization',
    scopeId: number,
    subleaseId: number,
    body?: Record<string, unknown>,
  ) {
    return api(`${buildBase(scopeType, scopeId)}/parking/subleases/${subleaseId}/approve`, {
      method: 'PATCH',
      body,
    })
  }

  async function getSubleasePayments(
    scopeType: 'team' | 'organization',
    scopeId: number,
    subleaseId: number,
    params?: Record<string, unknown>,
  ) {
    const q = new URLSearchParams()
    if (params)
      for (const [k, v] of Object.entries(params)) {
        if (v != null) q.set(k, String(v))
      }
    const qs = q.toString()
    return api<{ data: SubleasePaymentResponse[] }>(
      `${buildBase(scopeType, scopeId)}/parking/subleases/${subleaseId}/payments${qs ? `?${qs}` : ''}`,
    )
  }

  async function terminateSublease(
    scopeType: 'team' | 'organization',
    scopeId: number,
    subleaseId: number,
    body?: Record<string, unknown>,
  ) {
    return api(`${buildBase(scopeType, scopeId)}/parking/subleases/${subleaseId}/terminate`, {
      method: 'PATCH',
      body,
    })
  }

  // === Visitor Reservations ===
  async function getVisitorReservations(
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
    return api<{ data: VisitorReservationResponse[] }>(
      `${buildBase(scopeType, scopeId)}/parking/visitor-reservations${qs ? `?${qs}` : ''}`,
    )
  }

  async function createVisitorReservation(
    scopeType: 'team' | 'organization',
    scopeId: number,
    body: Record<string, unknown>,
  ) {
    return api<{ data: VisitorReservationResponse }>(
      `${buildBase(scopeType, scopeId)}/parking/visitor-reservations`,
      { method: 'POST', body },
    )
  }

  async function getVisitorReservationAvailability(
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
    return api(
      `${buildBase(scopeType, scopeId)}/parking/visitor-reservations/availability${qs ? `?${qs}` : ''}`,
    )
  }

  async function getVisitorReservation(
    scopeType: 'team' | 'organization',
    scopeId: number,
    reservationId: number,
  ) {
    return api<{ data: VisitorReservationResponse }>(
      `${buildBase(scopeType, scopeId)}/parking/visitor-reservations/${reservationId}`,
    )
  }

  async function deleteVisitorReservation(
    scopeType: 'team' | 'organization',
    scopeId: number,
    reservationId: number,
  ) {
    return api(`${buildBase(scopeType, scopeId)}/parking/visitor-reservations/${reservationId}`, {
      method: 'DELETE',
    })
  }

  async function approveVisitorReservation(
    scopeType: 'team' | 'organization',
    scopeId: number,
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
    scopeId: number,
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
    scopeId: number,
    reservationId: number,
  ) {
    return api(
      `${buildBase(scopeType, scopeId)}/parking/visitor-reservations/${reservationId}/check-in`,
      { method: 'PATCH' },
    )
  }

  async function completeVisitorReservation(
    scopeType: 'team' | 'organization',
    scopeId: number,
    reservationId: number,
  ) {
    return api(
      `${buildBase(scopeType, scopeId)}/parking/visitor-reservations/${reservationId}/complete`,
      { method: 'PATCH' },
    )
  }

  // === Visitor Recurring ===
  async function getVisitorRecurring(scopeType: 'team' | 'organization', scopeId: number) {
    return api<{ data: VisitorRecurringResponse[] }>(
      `${buildBase(scopeType, scopeId)}/parking/visitor-recurring`,
    )
  }

  async function createVisitorRecurring(
    scopeType: 'team' | 'organization',
    scopeId: number,
    body: Record<string, unknown>,
  ) {
    return api<{ data: VisitorRecurringResponse }>(
      `${buildBase(scopeType, scopeId)}/parking/visitor-recurring`,
      { method: 'POST', body },
    )
  }

  async function updateVisitorRecurring(
    scopeType: 'team' | 'organization',
    scopeId: number,
    recurringId: number,
    body: Record<string, unknown>,
  ) {
    return api<{ data: VisitorRecurringResponse }>(
      `${buildBase(scopeType, scopeId)}/parking/visitor-recurring/${recurringId}`,
      { method: 'PUT', body },
    )
  }

  async function deleteVisitorRecurring(
    scopeType: 'team' | 'organization',
    scopeId: number,
    recurringId: number,
  ) {
    return api(`${buildBase(scopeType, scopeId)}/parking/visitor-recurring/${recurringId}`, {
      method: 'DELETE',
    })
  }

  // === Watchlist ===
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

  // === Personal Vehicles (user scope) ===
  async function getMyVehicles() {
    return api<{ data: VehicleResponse[] }>('/api/v1/users/me/vehicles')
  }

  async function addVehicle(body: Record<string, unknown>) {
    return api('/api/v1/users/me/vehicles', { method: 'POST', body })
  }

  return {
    // Spaces
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
    // Applications
    getApplications,
    createApplication,
    deleteApplication,
    approveApplication,
    rejectApplication,
    runApplicationLottery,
    // Listings
    getListings,
    createListing,
    getListing,
    updateListing,
    deleteListing,
    applyToListing,
    transferListing,
    // Settings & Stats
    getParkingSettings,
    updateParkingSettings,
    getParkingStats,
    // Subleases
    getSubleases,
    createSublease,
    getSublease,
    updateSublease,
    deleteSublease,
    applyToSublease,
    approveSublease,
    getSubleasePayments,
    terminateSublease,
    // Visitor Reservations
    getVisitorReservations,
    createVisitorReservation,
    getVisitorReservationAvailability,
    getVisitorReservation,
    deleteVisitorReservation,
    approveVisitorReservation,
    rejectVisitorReservation,
    checkInVisitorReservation,
    completeVisitorReservation,
    // Visitor Recurring
    getVisitorRecurring,
    createVisitorRecurring,
    updateVisitorRecurring,
    deleteVisitorRecurring,
    // Watchlist
    getWatchlist,
    addToWatchlist,
    removeFromWatchlist,
    // Personal Vehicles
    getMyVehicles,
    addVehicle,
  }
}
