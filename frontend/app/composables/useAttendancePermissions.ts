import { computed, ref, type Ref } from 'vue'
import type { AttendancePermissions } from '~/composables/useAttendancePermissionsApi'

/** API エラーが HTTP 403 かを判定する（ofetch の statusCode / status の両方を見る）。 */
export function isForbiddenError(e: unknown): boolean {
  const err = e as { statusCode?: number; status?: number } | null
  return err?.statusCode === 403 || err?.status === 403
}

/**
 * 学校出欠の教員用画面の出し分け（CMP-261001-0630 AC-18）。
 *
 * FE は独自にロールを判定せず、BE の判定結果 API（GET /attendance/permissions）の値のみで出し分ける。
 * 403 は握りつぶさず forbidden として保持し、画面側で「権限がありません」を明示する。
 * 取得に失敗した場合は fail-closed（全て false）とし、共通エラーハンドラへ渡す。
 */
export function useAttendancePermissions(teamId: Ref<string>) {
  const api = useAttendancePermissionsApi()
  const { handleApiError } = useErrorHandler()

  const permissions = ref<AttendancePermissions | null>(null)
  const loaded = ref(false)
  const forbidden = ref(false)

  const canView = computed(() => permissions.value?.canView === true)
  const canRecordDaily = computed(() => permissions.value?.canRecordDaily === true)
  const canRecordPeriod = computed(() => permissions.value?.canRecordPeriod === true)

  async function loadPermissions(): Promise<void> {
    try {
      permissions.value = await api.getPermissions(teamId.value)
      forbidden.value = false
    } catch (e) {
      permissions.value = null
      if (isForbiddenError(e)) {
        forbidden.value = true
      } else {
        handleApiError(e, 'school.attendance.permissions.load')
      }
    } finally {
      loaded.value = true
    }
  }

  return {
    permissions,
    loaded,
    forbidden,
    canView,
    canRecordDaily,
    canRecordPeriod,
    loadPermissions,
  }
}
