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
 * 取得失敗（loadFailed）は拒否（権限なし）と区別し、画面は再試行可能なエラー状態を出す。
 * 照会中 / 許可 / 拒否 / 取得失敗 の4状態は ready・loadFailed・forbidden・canView で表す。
 */
export function useAttendancePermissions(teamId: Ref<string>) {
  const api = useAttendancePermissionsApi()
  const { handleApiError } = useErrorHandler()

  const permissions = ref<AttendancePermissions | null>(null)
  const loaded = ref(false)
  const forbidden = ref(false)
  /** 403 以外で権限照会に失敗した（権限なしとは別。再試行で復旧しうる）。 */
  const loadFailed = ref(false)
  /** 照会が完了し、成功または 403 で結論が出ている（失敗・照会中は false）。 */
  const ready = computed(() => loaded.value && !loadFailed.value)

  const canView = computed(() => permissions.value?.canView === true)
  const canRecordDaily = computed(() => permissions.value?.canRecordDaily === true)
  const canRecordPeriod = computed(() => permissions.value?.canRecordPeriod === true)

  async function loadPermissions(): Promise<void> {
    loaded.value = false
    loadFailed.value = false
    try {
      permissions.value = await api.getPermissions(teamId.value)
      forbidden.value = false
    } catch (e) {
      permissions.value = null
      if (isForbiddenError(e)) {
        forbidden.value = true
      } else {
        loadFailed.value = true
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
    loadFailed,
    ready,
    canView,
    canRecordDaily,
    canRecordPeriod,
    loadPermissions,
  }
}
