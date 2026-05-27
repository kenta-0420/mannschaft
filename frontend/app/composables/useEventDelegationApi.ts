/**
 * F03.10 代理出席 — イベント代理出席 API。
 *
 * 提供する関数:
 * - createDelegation    POST /api/v1/events/{eventId}/delegations
 * - deleteMyDelegation  DELETE /api/v1/events/{eventId}/delegations/me
 * - fetchDelegations    GET /api/v1/events/{eventId}/delegations（管理者用）
 * - fetchMyDelegation   GET /api/v1/events/{eventId}/delegations/me
 * - acceptDelegation    PATCH /api/v1/event-delegations/{id}/accept
 * - rejectDelegation    PATCH /api/v1/event-delegations/{id}/reject
 * - proxyCheckin        POST /api/v1/events/{eventId}/delegations/{delegationId}/checkin
 */
import type {
  CreateEventDelegationRequest,
  EventDelegationListResponse,
  EventDelegationMeResponse,
  EventDelegationResponse,
  ProxyCheckinResponse,
} from '~/types/event'

export function useEventDelegationApi() {
  const api = useApi()

  async function createDelegation(eventId: number, body: CreateEventDelegationRequest) {
    return api<{ data: EventDelegationResponse }>(
      `/api/v1/events/${eventId}/delegations`,
      { method: 'POST', body },
    )
  }

  async function deleteMyDelegation(eventId: number) {
    return api(`/api/v1/events/${eventId}/delegations/me`, { method: 'DELETE' })
  }

  async function fetchDelegations(eventId: number, page = 1, size = 20) {
    return api<EventDelegationListResponse>(
      `/api/v1/events/${eventId}/delegations`,
      { query: { page, size } },
    )
  }

  async function fetchMyDelegation(eventId: number) {
    return api<{ data: EventDelegationMeResponse }>(
      `/api/v1/events/${eventId}/delegations/me`,
    )
  }

  async function acceptDelegation(delegationId: string) {
    return api<{ data: EventDelegationResponse }>(
      `/api/v1/event-delegations/${delegationId}/accept`,
      { method: 'PATCH' },
    )
  }

  async function rejectDelegation(delegationId: string) {
    return api<{ data: EventDelegationResponse }>(
      `/api/v1/event-delegations/${delegationId}/reject`,
      { method: 'PATCH' },
    )
  }

  async function proxyCheckin(eventId: number, delegationId: string) {
    return api<{ data: ProxyCheckinResponse }>(
      `/api/v1/events/${eventId}/delegations/${delegationId}/checkin`,
      { method: 'POST' },
    )
  }

  return {
    createDelegation,
    deleteMyDelegation,
    fetchDelegations,
    fetchMyDelegation,
    acceptDelegation,
    rejectDelegation,
    proxyCheckin,
  }
}
