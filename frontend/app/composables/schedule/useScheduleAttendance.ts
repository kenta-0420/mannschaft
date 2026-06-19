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
import type { components } from '~/types/generated'

/**
 * F03.1 (B) 組織出欠のステータス別件数。生成型をそのまま利用する。
 * 生成型 components['schemas']['TeamBreakdownCounts']。
 */
export type AttendanceBreakdownCounts = components['schemas']['TeamBreakdownCounts']

/**
 * F03.1 (B) 組織出欠のチーム別内訳 1 行。生成型をそのまま利用する。
 *
 * BE の schedule.dto.AttendanceTeamBreakdownResponse.TeamBreakdownItem を
 * @Schema(name="AttendanceTeamBreakdownItem") で survey 側と分離したため、
 * 出欠形（attending/partial/absent/undecided）が独立した生成型として得られる。
 * 生成型 components['schemas']['AttendanceTeamBreakdownItem']。
 */
export type AttendanceTeamBreakdownItem = components['schemas']['AttendanceTeamBreakdownItem']

/**
 * F03.1 (B) 組織出欠のチーム別内訳レスポンス。生成型をそのまま利用する。
 * 生成型 components['schemas']['AttendanceTeamBreakdownResponse']。
 */
export type AttendanceTeamBreakdownResponse =
  components['schemas']['AttendanceTeamBreakdownResponse']

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

  /**
   * F03.1 (B) 組織出欠のチーム別内訳（by_team）を取得する。
   * 認可: 組織 ADMIN 限定（非 ADMIN は 403）。トグル OFF（既定）は本 EP ではなく従来の集計を使う。
   * 設計書: docs/features/F03.1（出欠） / PR #1666
   */
  async function getAttendanceTeamBreakdown(orgPublicId: string, scheduleId: number) {
    return api<{ data: AttendanceTeamBreakdownResponse }>(
      `/api/v1/organizations/${orgPublicId}/schedules/${scheduleId}/attendances/team-breakdown`,
    )
  }

  /**
   * F03.1 (B) 組織出欠のチーム別内訳 CSV エクスポート。
   * 認可: 組織 ADMIN 限定。Blob として受け取る。
   */
  async function exportAttendanceTeamBreakdownCsv(orgPublicId: string, scheduleId: number) {
    return api(
      `/api/v1/organizations/${orgPublicId}/schedules/${scheduleId}/attendances/team-breakdown/export`,
      { responseType: 'blob' },
    )
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
    getAttendanceTeamBreakdown,
    exportAttendanceTeamBreakdownCsv,
    bulkUpdateAttendances,
    createCrossInvite,
    deleteCrossInvite,
    getScheduleStats,
  }
}
