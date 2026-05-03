import type {
  CheckinRequest,
  CheckinResponse,
  CreateEventRequest,
  EventCreateInviteTokenRequest,
  CreateRegistrationRequest,
  CreateTicketTypeRequest,
  CreateTimetableItemRequest,
  EventDetailResponse,
  EventResponse,
  GuestRegistrationRequest,
  EventInviteTokenResponse,
  RegistrationResponse,
  ReorderTimetableRequest,
  SelfCheckinRequest,
  TicketTypeResponse,
  TimetableItemResponse,
  UpdateEventRequest,
  UpdateTicketTypeRequest,
  UpdateTimetableItemRequest,
} from '~/types/event'
import type { PageMeta } from '~/types/api'

interface EventListParams {
  status?: string
  page?: number
  size?: number
}

export function useEventApi() {
  const api = useApi()

  function buildBase(scopeType: 'team' | 'organization', scopeId: number) {
    return scopeType === 'team' ? `/api/v1/teams/${scopeId}` : `/api/v1/organizations/${scopeId}`
  }

  // === Events (scoped) ===
  async function listEvents(
    scopeType: 'team' | 'organization',
    scopeId: number,
    params?: EventListParams,
  ) {
    const query = new URLSearchParams()
    if (params?.status) query.set('status', params.status)
    query.set('page', String(params?.page ?? 0))
    query.set('size', String(params?.size ?? 20))
    return api<{ data: EventResponse[]; meta: PageMeta }>(
      `${buildBase(scopeType, scopeId)}/events?${query}`,
    )
  }

  async function createEvent(
    scopeType: 'team' | 'organization',
    scopeId: number,
    body: CreateEventRequest,
  ) {
    return api<{ data: EventDetailResponse }>(`${buildBase(scopeType, scopeId)}/events`, {
      method: 'POST',
      body,
    })
  }

  async function getEvent(scopeType: 'team' | 'organization', scopeId: number, eventId: number) {
    return api<{ data: EventDetailResponse }>(`${buildBase(scopeType, scopeId)}/events/${eventId}`)
  }

  async function updateEvent(
    scopeType: 'team' | 'organization',
    scopeId: number,
    eventId: number,
    body: UpdateEventRequest,
  ) {
    return api<{ data: EventDetailResponse }>(
      `${buildBase(scopeType, scopeId)}/events/${eventId}`,
      { method: 'PATCH', body },
    )
  }

  async function deleteEvent(scopeType: 'team' | 'organization', scopeId: number, eventId: number) {
    return api(`${buildBase(scopeType, scopeId)}/events/${eventId}`, { method: 'DELETE' })
  }

  async function cancelEvent(scopeType: 'team' | 'organization', scopeId: number, eventId: number) {
    return api<{ data: EventDetailResponse }>(
      `${buildBase(scopeType, scopeId)}/events/${eventId}/cancel`,
      { method: 'POST' },
    )
  }

  async function publishEvent(
    scopeType: 'team' | 'organization',
    scopeId: number,
    eventId: number,
  ) {
    return api<{ data: EventDetailResponse }>(
      `${buildBase(scopeType, scopeId)}/events/${eventId}/publish`,
      { method: 'POST' },
    )
  }

  async function closeRegistration(
    scopeType: 'team' | 'organization',
    scopeId: number,
    eventId: number,
  ) {
    return api<{ data: EventDetailResponse }>(
      `${buildBase(scopeType, scopeId)}/events/${eventId}/close-registration`,
      { method: 'POST' },
    )
  }

  async function openRegistration(
    scopeType: 'team' | 'organization',
    scopeId: number,
    eventId: number,
  ) {
    return api<{ data: EventDetailResponse }>(
      `${buildBase(scopeType, scopeId)}/events/${eventId}/open-registration`,
      { method: 'POST' },
    )
  }

  // === Registrations ===
  async function listRegistrations(eventId: number, params?: { page?: number; size?: number }) {
    const query = new URLSearchParams()
    query.set('page', String(params?.page ?? 0))
    query.set('size', String(params?.size ?? 20))
    return api<{ data: RegistrationResponse[]; meta: PageMeta }>(
      `/api/v1/events/${eventId}/registrations?${query}`,
    )
  }

  async function createRegistration(eventId: number, body: CreateRegistrationRequest) {
    return api<{ data: RegistrationResponse }>(`/api/v1/events/${eventId}/registrations`, {
      method: 'POST',
      body,
    })
  }

  async function getRegistration(eventId: number, registrationId: number) {
    return api<{ data: RegistrationResponse }>(
      `/api/v1/events/${eventId}/registrations/${registrationId}`,
    )
  }

  async function approveRegistration(eventId: number, registrationId: number) {
    return api<{ data: RegistrationResponse }>(
      `/api/v1/events/${eventId}/registrations/${registrationId}/approve`,
      { method: 'POST' },
    )
  }

  async function rejectRegistration(eventId: number, registrationId: number) {
    return api<{ data: RegistrationResponse }>(
      `/api/v1/events/${eventId}/registrations/${registrationId}/reject`,
      { method: 'POST' },
    )
  }

  async function cancelRegistration(eventId: number, registrationId: number) {
    return api<{ data: RegistrationResponse }>(
      `/api/v1/events/${eventId}/registrations/${registrationId}/cancel`,
      { method: 'POST' },
    )
  }

  async function createGuestRegistration(eventId: number, body: GuestRegistrationRequest) {
    return api<{ data: RegistrationResponse }>(`/api/v1/events/${eventId}/registrations/guest`, {
      method: 'POST',
      body,
    })
  }

  // === Checkins ===
  async function listCheckins(eventId: number, params?: { page?: number; size?: number }) {
    const query = new URLSearchParams()
    query.set('page', String(params?.page ?? 0))
    query.set('size', String(params?.size ?? 20))
    return api<{ data: CheckinResponse[]; meta: PageMeta }>(
      `/api/v1/events/${eventId}/checkins?${query}`,
    )
  }

  async function getCheckinCount(eventId: number) {
    return api<{ data: number }>(`/api/v1/events/${eventId}/checkins/count`)
  }

  async function checkin(body: CheckinRequest) {
    return api<{ data: CheckinResponse }>(`/api/v1/events/checkin`, { method: 'POST', body })
  }

  async function selfCheckin(body: SelfCheckinRequest) {
    return api(`/api/v1/events/checkin/self`, { method: 'POST', body })
  }

  // === Ticket Types ===
  async function listTicketTypes(eventId: number) {
    return api<{ data: TicketTypeResponse[] }>(`/api/v1/events/${eventId}/ticket-types`)
  }

  async function createTicketType(eventId: number, body: CreateTicketTypeRequest) {
    return api<{ data: TicketTypeResponse }>(`/api/v1/events/${eventId}/ticket-types`, {
      method: 'POST',
      body,
    })
  }

  async function updateTicketType(
    eventId: number,
    ticketTypeId: number,
    body: UpdateTicketTypeRequest,
  ) {
    return api<{ data: TicketTypeResponse }>(
      `/api/v1/events/${eventId}/ticket-types/${ticketTypeId}`,
      { method: 'PATCH', body },
    )
  }

  async function getTicketType(eventId: number, ticketTypeId: number) {
    return api<{ data: TicketTypeResponse }>(
      `/api/v1/events/${eventId}/ticket-types/${ticketTypeId}`,
    )
  }

  // === Tickets ===
  async function listTickets(eventId: number, params?: { page?: number; size?: number }) {
    const query = new URLSearchParams()
    query.set('page', String(params?.page ?? 0))
    query.set('size', String(params?.size ?? 20))
    return api<{ data: unknown[]; meta: PageMeta }>(`/api/v1/events/${eventId}/tickets?${query}`)
  }

  async function getTicketByQr(eventId: number, qrToken: string) {
    return api<{ data: unknown }>(
      `/api/v1/events/${eventId}/tickets/by-qr?qrToken=${encodeURIComponent(qrToken)}`,
    )
  }

  async function cancelTicket(eventId: number, ticketId: number) {
    return api(`/api/v1/events/${eventId}/tickets/${ticketId}/cancel`, { method: 'POST' })
  }

  // === Timetable (Event) ===
  async function getTimetable(eventId: number) {
    return api<{ data: TimetableItemResponse[] }>(`/api/v1/events/${eventId}/timetable`)
  }

  async function createTimetableItem(eventId: number, body: CreateTimetableItemRequest) {
    return api<{ data: TimetableItemResponse }>(`/api/v1/events/${eventId}/timetable`, {
      method: 'POST',
      body,
    })
  }

  async function updateTimetableItem(
    eventId: number,
    itemId: number,
    body: UpdateTimetableItemRequest,
  ) {
    return api<{ data: TimetableItemResponse }>(`/api/v1/events/${eventId}/timetable/${itemId}`, {
      method: 'PATCH',
      body,
    })
  }

  async function deleteTimetableItem(eventId: number, itemId: number) {
    return api(`/api/v1/events/${eventId}/timetable/${itemId}`, { method: 'DELETE' })
  }

  async function reorderTimetable(eventId: number, body: ReorderTimetableRequest) {
    return api(`/api/v1/events/${eventId}/timetable/reorder`, { method: 'PUT', body })
  }

  // === Invite Tokens ===
  async function listInviteTokens(eventId: number) {
    return api<{ data: EventInviteTokenResponse[] }>(`/api/v1/events/${eventId}/invite-tokens`)
  }

  async function createInviteToken(eventId: number, body: EventCreateInviteTokenRequest) {
    return api<{ data: EventInviteTokenResponse }>(`/api/v1/events/${eventId}/invite-tokens`, {
      method: 'POST',
      body,
    })
  }

  async function deactivateInviteToken(eventId: number, tokenId: number) {
    return api(`/api/v1/events/${eventId}/invite-tokens/${tokenId}/deactivate`, { method: 'POST' })
  }

  return {
    listEvents,
    createEvent,
    getEvent,
    updateEvent,
    deleteEvent,
    cancelEvent,
    publishEvent,
    closeRegistration,
    openRegistration,
    listRegistrations,
    createRegistration,
    getRegistration,
    approveRegistration,
    rejectRegistration,
    cancelRegistration,
    createGuestRegistration,
    listCheckins,
    getCheckinCount,
    checkin,
    selfCheckin,
    listTicketTypes,
    createTicketType,
    updateTicketType,
    getTicketType,
    listTickets,
    getTicketByQr,
    cancelTicket,
    getTimetable,
    createTimetableItem,
    updateTimetableItem,
    deleteTimetableItem,
    reorderTimetable,
    listInviteTokens,
    createInviteToken,
    deactivateInviteToken,
  }
}
