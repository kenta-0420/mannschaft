/**
 * F03.17 キープ（日付未定の予定）API composable。
 *
 * 設計書: docs/features/F03.17_schedule_keep.md §4（API 契約）
 *
 * チーム／組織／個人の3スコープで同形の API を持つため、`scopeType` + `scopeId`
 * （チーム/組織は publicId=slug、個人は不要）でベースパスを組み立てる。
 * 型はすべて生成型（`~/types/generated`）を使用する（手書き断言禁止）。
 */
import type { components } from '~/types/generated'

export type ScheduleKeepScope = 'team' | 'organization' | 'personal'

export type ScheduleKeepResponse = components['schemas']['ScheduleKeepResponse']
export type CreateScheduleKeepRequest = components['schemas']['CreateScheduleKeepRequest']
export type ConvertScheduleKeepRequest = components['schemas']['ConvertScheduleKeepRequest']
export type ConvertScheduleKeepResponse = components['schemas']['ConvertScheduleKeepResponse']

type ApiResponseScheduleKeep = components['schemas']['ApiResponseScheduleKeepResponse']
type ApiResponseListScheduleKeep = components['schemas']['ApiResponseListScheduleKeepResponse']
type ApiResponseConvertScheduleKeep = components['schemas']['ApiResponseConvertScheduleKeepResponse']

export function useScheduleKeepApi() {
  const api = useApi()

  function buildBase(scopeType: ScheduleKeepScope, scopeId?: string): string {
    if (scopeType === 'team') return `/api/v1/teams/${scopeId}`
    if (scopeType === 'organization') return `/api/v1/organizations/${scopeId}`
    return '/api/v1/me'
  }

  async function listScheduleKeeps(
    scopeType: ScheduleKeepScope,
    scopeId: string | undefined,
    status: 'KEPT' | 'SCHEDULED' | 'ARCHIVED' | 'ALL' = 'KEPT',
  ) {
    const query = new URLSearchParams({ status })
    return api<ApiResponseListScheduleKeep>(
      `${buildBase(scopeType, scopeId)}/schedule-keeps?${query}`,
    )
  }

  async function createScheduleKeep(
    scopeType: ScheduleKeepScope,
    scopeId: string | undefined,
    body: CreateScheduleKeepRequest,
  ) {
    return api<ApiResponseScheduleKeep>(`${buildBase(scopeType, scopeId)}/schedule-keeps`, {
      method: 'POST',
      body,
    })
  }

  async function updateScheduleKeep(
    scopeType: ScheduleKeepScope,
    scopeId: string | undefined,
    keepId: string,
    body: Partial<CreateScheduleKeepRequest>,
  ) {
    return api<ApiResponseScheduleKeep>(
      `${buildBase(scopeType, scopeId)}/schedule-keeps/${keepId}`,
      { method: 'PATCH', body },
    )
  }

  async function deleteScheduleKeep(
    scopeType: ScheduleKeepScope,
    scopeId: string | undefined,
    keepId: string,
  ) {
    return api(`${buildBase(scopeType, scopeId)}/schedule-keeps/${keepId}`, {
      method: 'DELETE',
    })
  }

  async function convertScheduleKeep(
    scopeType: ScheduleKeepScope,
    scopeId: string | undefined,
    keepId: string,
    body: ConvertScheduleKeepRequest,
  ) {
    return api<ApiResponseConvertScheduleKeep>(
      `${buildBase(scopeType, scopeId)}/schedule-keeps/${keepId}/convert`,
      { method: 'POST', body },
    )
  }

  async function revertScheduleKeep(
    scopeType: ScheduleKeepScope,
    scopeId: string | undefined,
    keepId: string,
  ) {
    return api<ApiResponseScheduleKeep>(
      `${buildBase(scopeType, scopeId)}/schedule-keeps/${keepId}/revert`,
      { method: 'POST' },
    )
  }

  async function archiveScheduleKeep(
    scopeType: ScheduleKeepScope,
    scopeId: string | undefined,
    keepId: string,
  ) {
    return api<ApiResponseScheduleKeep>(
      `${buildBase(scopeType, scopeId)}/schedule-keeps/${keepId}/archive`,
      { method: 'POST' },
    )
  }

  async function restoreScheduleKeep(
    scopeType: ScheduleKeepScope,
    scopeId: string | undefined,
    keepId: string,
  ) {
    return api<ApiResponseScheduleKeep>(
      `${buildBase(scopeType, scopeId)}/schedule-keeps/${keepId}/restore`,
      { method: 'POST' },
    )
  }

  async function reorderScheduleKeeps(
    scopeType: ScheduleKeepScope,
    scopeId: string | undefined,
    orderedIds: string[],
  ) {
    return api(`${buildBase(scopeType, scopeId)}/schedule-keeps/reorder`, {
      method: 'POST',
      body: { orderedIds },
    })
  }

  return {
    listScheduleKeeps,
    createScheduleKeep,
    updateScheduleKeep,
    deleteScheduleKeep,
    convertScheduleKeep,
    revertScheduleKeep,
    archiveScheduleKeep,
    restoreScheduleKeep,
    reorderScheduleKeeps,
  }
}
