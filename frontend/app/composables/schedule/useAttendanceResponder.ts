/**
 * 出欠回答の共通ロジック composable。
 *
 * 出欠回答（PUT /api/v1/{teams|organizations}/{scopeId}/schedules/{scheduleId}/attendances/me）の
 * 呼び出し・成功/失敗トースト表示を一箇所に集約する。
 *
 * これまで {@link AttendancePanel} が内包していた respond ロジックを切り出し、
 * デスクトップの詳細パネル（AttendancePanel）とモバイルのリストビュー行内 RSVP
 * （ScheduleListRow）の両方から再利用する（二重実装の回避）。
 */
export function useAttendanceResponder(scopeType: 'team' | 'organization', scopeId: string) {
  const scheduleApi = useScheduleApi()
  const notification = useNotification()
  const { t } = useI18n()

  const responding = ref(false)

  /**
   * 指定スケジュールへ出欠を回答する。
   *
   * @param scheduleId 対象スケジュール ID
   * @param status     'YES' / 'NO' / 'MAYBE'
   * @param comment    任意コメント（空文字/空白のみは送らない）
   * @returns 成功なら true、失敗なら false（呼び出し側の選択状態更新の可否判定に使う）
   */
  async function respond(scheduleId: number, status: string, comment?: string): Promise<boolean> {
    responding.value = true
    try {
      await scheduleApi.respondAttendance(scopeType, scopeId, scheduleId, {
        status,
        comment: comment?.trim() || undefined,
      })
      notification.success(t('schedule.attendance.respondSuccess'))
      return true
    } catch {
      notification.error(t('schedule.attendance.respondError'))
      return false
    } finally {
      responding.value = false
    }
  }

  return { responding, respond }
}
