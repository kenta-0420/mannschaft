import { useScheduleCrud } from './schedule/useScheduleCrud'
import { useScheduleAttendance } from './schedule/useScheduleAttendance'
import { useSchedulePersonal } from './schedule/useSchedulePersonal'
import { useScheduleAnalytics } from './schedule/useScheduleAnalytics'

/**
 * スケジュール機能の API ファサード composable。
 *
 * 内部はドメイン別の composable に分割されている:
 * - {@link useScheduleCrud}       — 共有スケジュール CRUD / カテゴリ / 招待 / カレンダー月次 / グローバル操作
 * - {@link useScheduleAttendance} — 出欠 / クロス招待 / スケジュール個別統計
 * - {@link useSchedulePersonal}   — 個人スケジュール（/me/schedules、/my/calendar）
 * - {@link useScheduleAnalytics}  — 年次計画 / パフォーマンス / 出欠統計（チーム・組織・個人）
 *
 * 呼び出し側との互換性のため、戻り値の公開関数群は分割前と完全一致させている。
 * 新規実装では、必要なドメインのみを直接 import することも推奨する。
 */
export function useScheduleApi() {
  const crud = useScheduleCrud()
  const attendance = useScheduleAttendance()
  const personal = useSchedulePersonal()
  const analytics = useScheduleAnalytics()

  return {
    // === Shared Schedule CRUD ===
    listSchedules: crud.listSchedules,
    getSchedule: crud.getSchedule,
    createSchedule: crud.createSchedule,
    updateSchedule: crud.updateSchedule,
    deleteSchedule: crud.deleteSchedule,
    cancelSchedule: crud.cancelSchedule,
    cancelScheduledTask: crud.cancelScheduledTask,
    // === Attendance ===
    getAttendances: attendance.getAttendances,
    respondAttendance: attendance.respondAttendance,
    exportAttendances: attendance.exportAttendances,
    getAttendanceTeamBreakdown: attendance.getAttendanceTeamBreakdown,
    exportAttendanceTeamBreakdownCsv: attendance.exportAttendanceTeamBreakdownCsv,
    bulkUpdateAttendances: attendance.bulkUpdateAttendances,
    // === Personal Schedule ===
    listPersonalSchedules: personal.listPersonalSchedules,
    createPersonalSchedule: personal.createPersonalSchedule,
    updatePersonalSchedule: personal.updatePersonalSchedule,
    deletePersonalSchedule: personal.deletePersonalSchedule,
    // === Calendar / Categories / Duplicate ===
    getCalendarMonth: crud.getCalendarMonth,
    getCalendarRange: crud.getCalendarRange,
    getCategories: crud.getCategories,
    createCategory: crud.createCategory,
    duplicateSchedule: crud.duplicateSchedule,
    // === Annual Schedule ===
    getAnnualSchedules: analytics.getAnnualSchedules,
    previewAnnualCopy: analytics.previewAnnualCopy,
    executeAnnualCopy: analytics.executeAnnualCopy,
    getAnnualCopyLogs: analytics.getAnnualCopyLogs,
    // === Cross Invite ===
    createCrossInvite: attendance.createCrossInvite,
    deleteCrossInvite: attendance.deleteCrossInvite,
    // === Performance ===
    getSchedulePerformance: analytics.getSchedulePerformance,
    bulkCreatePerformanceRecords: analytics.bulkCreatePerformanceRecords,
    // === Global Schedule Actions ===
    remindSchedule: crud.remindSchedule,
    respondToSchedule: crud.respondToSchedule,
    getScheduleStats: attendance.getScheduleStats,
    // === Schedule Invitations ===
    getScheduleInvitations: crud.getScheduleInvitations,
    acceptScheduleInvitation: crud.acceptScheduleInvitation,
    rejectScheduleInvitation: crud.rejectScheduleInvitation,
    confirmScheduleInvitation: crud.confirmScheduleInvitation,
    // === Attendance Stats ===
    getTeamAttendanceStats: analytics.getTeamAttendanceStats,
    exportTeamAttendanceStats: analytics.exportTeamAttendanceStats,
    getOrgAttendanceStats: analytics.getOrgAttendanceStats,
    exportOrgAttendanceStats: analytics.exportOrgAttendanceStats,
    getMyAttendanceStats: analytics.getMyAttendanceStats,
    // === My Calendar / Me Schedules ===
    getMyCalendar: personal.getMyCalendar,
    getMySchedules: personal.getMySchedules,
    getMyScheduleDetail: personal.getMyScheduleDetail,
    createMySchedule: personal.createMySchedule,
    updateMySchedule: personal.updateMySchedule,
    deleteMySchedule: personal.deleteMySchedule,
    batchDeleteMySchedules: personal.batchDeleteMySchedules,
  }
}
