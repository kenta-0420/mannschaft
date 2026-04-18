import type {
  EventRsvpResponseItem,
  EventRsvpSummary,
  SubmitRsvpRequest,
} from '~/types/event'

export function useEventRsvpApi() {
  const api = useApi()

  function buildBase(scopeType: 'team' | 'organization', scopeId: number) {
    return scopeType === 'team' ? `/api/v1/teams/${scopeId}` : `/api/v1/organizations/${scopeId}`
  }

  async function submitRsvp(
    scopeType: 'team' | 'organization',
    scopeId: number,
    eventId: number,
    body: SubmitRsvpRequest,
  ) {
    return api<{ data: EventRsvpResponseItem }>(
      `${buildBase(scopeType, scopeId)}/events/${eventId}/rsvp`,
      { method: 'POST', body },
    )
  }

  async function updateRsvp(
    scopeType: 'team' | 'organization',
    scopeId: number,
    eventId: number,
    body: SubmitRsvpRequest,
  ) {
    return api<{ data: EventRsvpResponseItem }>(
      `${buildBase(scopeType, scopeId)}/events/${eventId}/rsvp`,
      { method: 'PUT', body },
    )
  }

  async function fetchRsvpList(
    scopeType: 'team' | 'organization',
    scopeId: number,
    eventId: number,
  ) {
    return api<{ data: EventRsvpResponseItem[] }>(
      `${buildBase(scopeType, scopeId)}/events/${eventId}/rsvp`,
    )
  }

  async function fetchRsvpSummary(
    scopeType: 'team' | 'organization',
    scopeId: number,
    eventId: number,
  ) {
    return api<{ data: EventRsvpSummary }>(
      `${buildBase(scopeType, scopeId)}/events/${eventId}/rsvp/summary`,
    )
  }

  return {
    submitRsvp,
    updateRsvp,
    fetchRsvpList,
    fetchRsvpSummary,
  }
}
