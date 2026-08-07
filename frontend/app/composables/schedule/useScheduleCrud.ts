/**
 * 共有スケジュール CRUD / カテゴリ / 招待 / カレンダー月次 / グローバル操作。
 *
 * 提供する関数:
 * - CRUD:       listSchedules / getSchedule / createSchedule / updateSchedule / deleteSchedule / cancelSchedule / duplicateSchedule
 * - カテゴリ:   getCategories / createCategory
 * - 招待:       getScheduleInvitations / acceptScheduleInvitation / rejectScheduleInvitation / confirmScheduleInvitation
 * - カレンダー: getCalendarMonth / getCalendarRange
 * - グローバル: remindSchedule
 *
 * 出欠回答（respondToSchedule）は useScheduleAttendance.respondAttendance に一本化済み
 * （PATCH /api/v1/schedules/{id}/responses への重複実装だったため削除）。
 */
import type { ScheduleInvitationResponse } from '~/types/schedule'

export function useScheduleCrud() {
  const api = useApi()
  const { buildDayStartStr, buildDayEndStr } = useDatetime()

  function buildBase(scopeType: 'team' | 'organization', scopeId: string) {
    return scopeType === 'team' ? `/api/v1/teams/${scopeId}` : `/api/v1/organizations/${scopeId}`
  }

  // === Shared Schedule CRUD ===
  async function listSchedules(
    scopeType: 'team' | 'organization',
    scopeId: string,
    params?: {
      from?: string
      to?: string
      status?: string
      categoryId?: number
      page?: number
      size?: number
    },
  ) {
    const query = new URLSearchParams()
    if (params?.from) query.set('from', params.from)
    if (params?.to) query.set('to', params.to)
    if (params?.status) query.set('status', params.status)
    if (params?.categoryId) query.set('categoryId', String(params.categoryId))
    query.set('page', String(params?.page ?? 0))
    query.set('size', String(params?.size ?? 50))
    return api<{
      data: unknown[]
      meta: { page: number; size: number; totalElements: number; totalPages: number }
    }>(`${buildBase(scopeType, scopeId)}/schedules?${query}`)
  }

  async function getSchedule(
    scopeType: 'team' | 'organization',
    scopeId: string,
    scheduleId: number,
  ) {
    return api<{ data: unknown }>(`${buildBase(scopeType, scopeId)}/schedules/${scheduleId}`)
  }

  async function createSchedule(
    scopeType: 'team' | 'organization',
    scopeId: string,
    body: Record<string, unknown>,
  ) {
    return api<{ data: unknown }>(`${buildBase(scopeType, scopeId)}/schedules`, {
      method: 'POST',
      body,
    })
  }

  async function updateSchedule(
    scopeType: 'team' | 'organization',
    scopeId: string,
    scheduleId: number,
    body: Record<string, unknown>,
  ) {
    return api<{ data: unknown }>(`${buildBase(scopeType, scopeId)}/schedules/${scheduleId}`, {
      method: 'PATCH',
      body,
    })
  }

  async function deleteSchedule(
    scopeType: 'team' | 'organization',
    scopeId: string,
    scheduleId: number,
    editScope?: string,
  ) {
    const query = editScope ? `?editScope=${editScope}` : ''
    return api(`${buildBase(scopeType, scopeId)}/schedules/${scheduleId}${query}`, {
      method: 'DELETE',
    })
  }

  async function cancelSchedule(
    scopeType: 'team' | 'organization',
    scopeId: string,
    scheduleId: number,
  ) {
    return api(`${buildBase(scopeType, scopeId)}/schedules/${scheduleId}/cancel`, {
      method: 'POST',
    })
  }

  // === 機能55: 予約タスク取消（PENDING のみ・204/404/409） ===
  async function cancelScheduledTask(
    scopeType: 'team' | 'organization',
    scopeId: string,
    scheduleId: number,
    taskId: string,
  ) {
    return api(
      `${buildBase(scopeType, scopeId)}/schedules/${scheduleId}/scheduled-tasks/${taskId}`,
      { method: 'DELETE' },
    )
  }

  // === Duplicate ===
  async function duplicateSchedule(
    scopeType: 'team' | 'organization',
    scopeId: string,
    scheduleId: number,
  ) {
    return api<{ data: unknown }>(
      `${buildBase(scopeType, scopeId)}/schedules/${scheduleId}/duplicate`,
      { method: 'POST' },
    )
  }

  // === Calendar View ===
  async function getCalendarMonth(
    year: number,
    month: number,
    scopeType?: string,
    scopeId?: number,
  ) {
    const pad = (n: number) => String(n).padStart(2, '0')
    const lastDay = new Date(year, month, 0).getDate()
    // 暦日の月境界をユーザーTZの 00:00:00 / 23:59:59 として送る（Issue #2508 Phase 2）。
    // ナイーブ連結は America/Santiago 等の「深夜0時にTZが切り替わる地域」で非存在時刻となり、
    // 範囲の下端が1時間欠ける。
    const from = buildDayStartStr(`${year}-${pad(month)}-01`)
    const to = buildDayEndStr(`${year}-${pad(month)}-${pad(lastDay)}`)
    if (scopeType === 'TEAM' && scopeId) {
      const query = new URLSearchParams()
      query.set('from', from)
      query.set('to', to)
      return api<{ data: unknown }>(`/api/v1/teams/${scopeId}/schedules?${query}`)
    }
    const query = new URLSearchParams()
    query.set('from', from)
    query.set('to', to)
    return api<{ data: unknown }>(`/api/v1/my/calendar?${query}`)
  }

  async function getCalendarRange(from: string, to: string) {
    const query = new URLSearchParams()
    query.set('from', from)
    query.set('to', to)
    return api<{ data: unknown }>(`/api/v1/my/calendar?${query}`)
  }

  // === Event Categories ===
  async function getCategories(scopeType: 'team' | 'organization', scopeId: string) {
    return api<{ data: unknown[] }>(`${buildBase(scopeType, scopeId)}/event-categories`)
  }

  async function createCategory(
    scopeType: 'team' | 'organization',
    scopeId: string,
    body: { name: string; color: string },
  ) {
    return api<{ data: unknown }>(`${buildBase(scopeType, scopeId)}/event-categories`, {
      method: 'POST',
      body,
    })
  }

  // === Global Schedule Actions ===
  async function remindSchedule(scheduleId: number) {
    return api(`/api/v1/schedules/${scheduleId}/remind`, { method: 'POST' })
  }

  // === Schedule Invitations ===
  async function getScheduleInvitations(scopeType: 'team' | 'organization', scopeId: string) {
    return api<{ data: ScheduleInvitationResponse[] }>(
      `${buildBase(scopeType, scopeId)}/schedule-invitations`,
    )
  }

  async function acceptScheduleInvitation(
    scopeType: 'team' | 'organization',
    scopeId: string,
    invitationId: number,
  ) {
    return api(`${buildBase(scopeType, scopeId)}/schedule-invitations/${invitationId}/accept`, {
      method: 'POST',
    })
  }

  async function rejectScheduleInvitation(
    scopeType: 'team' | 'organization',
    scopeId: string,
    invitationId: number,
  ) {
    return api(`${buildBase(scopeType, scopeId)}/schedule-invitations/${invitationId}/reject`, {
      method: 'POST',
    })
  }

  async function confirmScheduleInvitation(teamId: string, invitationId: number) {
    return api(`/api/v1/teams/${teamId}/schedule-invitations/${invitationId}/confirm`, {
      method: 'POST',
    })
  }

  return {
    listSchedules,
    getSchedule,
    createSchedule,
    updateSchedule,
    deleteSchedule,
    cancelSchedule,
    cancelScheduledTask,
    duplicateSchedule,
    getCalendarMonth,
    getCalendarRange,
    getCategories,
    createCategory,
    remindSchedule,
    getScheduleInvitations,
    acceptScheduleInvitation,
    rejectScheduleInvitation,
    confirmScheduleInvitation,
  }
}
