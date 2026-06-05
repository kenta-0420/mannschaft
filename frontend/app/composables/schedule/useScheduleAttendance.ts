/**
 * スケジュール出欠 / クロス招待 / スケジュール統計（個別）。
 *
 * 提供する関数:
 * - 出欠:       getAttendances / respondAttendance / exportAttendances / bulkUpdateAttendances
 * - クロス招待: createCrossInvite / deleteCrossInvite
 * - 統計:       getScheduleStats（個別スケジュール集計）
 */
import type {
  BulkAttendanceRequest,
  CrossInviteRequest,
  ScheduleStatsResponse,
} from '~/types/schedule'

export function useScheduleAttendance() {
  const api = useApi()

  function buildBase(scopeType: 'team' | 'organization', scopeId: string) {
    return scopeType === 'team' ? `/api/v1/teams/${scopeId}` : `/api/v1/organizations/${scopeId}`
  }

  // === Attendance ===
  async function getAttendances(
    scopeType: 'team' | 'organization',
    scopeId: string,
    scheduleId: number,
  ) {
    return api<{ data: unknown[] }>(
      `${buildBase(scopeType, scopeId)}/schedules/${scheduleId}/attendances`,
    )
  }

  async function respondAttendance(
    scopeType: 'team' | 'organization',
    scopeId: string,
    scheduleId: number,
    body: { status: string; comment?: string },
  ) {
    return api(`${buildBase(scopeType, scopeId)}/schedules/${scheduleId}/attendances/me`, {
      method: 'PUT',
      body,
    })
  }

  async function exportAttendances(
    scopeType: 'team' | 'organization',
    scopeId: string,
    scheduleId: number,
  ) {
    return api(`${buildBase(scopeType, scopeId)}/schedules/${scheduleId}/attendances/export`, {
      responseType: 'blob',
    })
  }

  // === Bulk Attendance (teams only) ===
  async function bulkUpdateAttendances(
    teamId: string,
    scheduleId: number,
    body: BulkAttendanceRequest,
  ) {
    return api(`/api/v1/teams/${teamId}/schedules/${scheduleId}/attendances/bulk`, {
      method: 'PATCH',
      body,
    })
  }

  // === Cross Invite (teams only) ===
  async function createCrossInvite(teamId: string, scheduleId: number, body: CrossInviteRequest) {
    return api(`/api/v1/teams/${teamId}/schedules/${scheduleId}/cross-invite`, {
      method: 'POST',
      body,
    })
  }

  async function deleteCrossInvite(teamId: string, scheduleId: number, invitationId: number) {
    return api(`/api/v1/teams/${teamId}/schedules/${scheduleId}/cross-invite/${invitationId}`, {
      method: 'DELETE',
    })
  }

  // === Schedule Stats ===
  async function getScheduleStats(scheduleId: number) {
    return api<{ data: ScheduleStatsResponse }>(`/api/v1/schedules/${scheduleId}/stats`)
  }

  return {
    getAttendances,
    respondAttendance,
    exportAttendances,
    bulkUpdateAttendances,
    createCrossInvite,
    deleteCrossInvite,
    getScheduleStats,
  }
}
