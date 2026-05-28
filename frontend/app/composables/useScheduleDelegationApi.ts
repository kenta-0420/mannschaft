/**
 * F03.10 代理出席 — スケジュール代理出席 API。
 *
 * 提供する関数:
 * - createDelegation    POST /api/v1/schedules/{scheduleId}/delegations
 * - deleteMyDelegation  DELETE /api/v1/schedules/{scheduleId}/delegations/me
 * - fetchDelegations    GET /api/v1/schedules/{scheduleId}/delegations（管理者用）
 * - fetchMyDelegation   GET /api/v1/schedules/{scheduleId}/delegations/me
 * - acceptDelegation    PATCH /api/v1/schedule-delegations/{id}/accept
 * - rejectDelegation    PATCH /api/v1/schedule-delegations/{id}/reject
 */
import type {
  CreateScheduleDelegationRequest,
  ScheduleDelegationListResponse,
  ScheduleDelegationMeResponse,
  ScheduleDelegationResponse,
} from '~/types/schedule'

export function useScheduleDelegationApi() {
  const api = useApi()

  async function createDelegation(scheduleId: number, body: CreateScheduleDelegationRequest) {
    return api<{ data: ScheduleDelegationResponse }>(
      `/api/v1/schedules/${scheduleId}/delegations`,
      { method: 'POST', body },
    )
  }

  async function deleteMyDelegation(scheduleId: number) {
    return api(`/api/v1/schedules/${scheduleId}/delegations/me`, { method: 'DELETE' })
  }

  async function fetchDelegations(scheduleId: number, page = 1, size = 20) {
    return api<ScheduleDelegationListResponse>(
      `/api/v1/schedules/${scheduleId}/delegations`,
      { query: { page, size } },
    )
  }

  async function fetchMyDelegation(scheduleId: number) {
    return api<{ data: ScheduleDelegationMeResponse }>(
      `/api/v1/schedules/${scheduleId}/delegations/me`,
    )
  }

  async function acceptDelegation(delegationId: string) {
    return api<{ data: ScheduleDelegationResponse }>(
      `/api/v1/schedule-delegations/${delegationId}/accept`,
      { method: 'PATCH' },
    )
  }

  async function rejectDelegation(delegationId: string) {
    return api<{ data: ScheduleDelegationResponse }>(
      `/api/v1/schedule-delegations/${delegationId}/reject`,
      { method: 'PATCH' },
    )
  }

  return {
    createDelegation,
    deleteMyDelegation,
    fetchDelegations,
    fetchMyDelegation,
    acceptDelegation,
    rejectDelegation,
  }
}
