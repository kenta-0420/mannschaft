import { ref, type Ref } from 'vue'
import { isForbiddenError } from '~/composables/useAttendancePermissions'
import type {
  CandidateItem,
  PeriodAttendanceEntry,
  PeriodAttendanceSummary,
} from '~/types/school'

export function usePeriodAttendance(teamId: Ref<string>) {
  const api = usePeriodAttendanceApi()
  const { success: notifySuccess } = useNotification()
  const { handleApiError } = useErrorHandler()
  const { t } = useI18n()

  const candidates = ref<CandidateItem[]>([])
  const loading = ref(false)
  const submitting = ref(false)
  const lastSummary = ref<PeriodAttendanceSummary | null>(null)
  // 閲覧が 403 のとき true。握りつぶさず画面で「権限がありません」を明示する（AC-18）
  const forbidden = ref(false)

  async function loadCandidates(periodNumber: number, date: string): Promise<void> {
    loading.value = true
    try {
      const res = await api.getPeriodCandidates(teamId.value, periodNumber, date)
      candidates.value = res.candidates
      forbidden.value = false
    } catch (e) {
      if (isForbiddenError(e)) {
        forbidden.value = true
        return
      }
      handleApiError(e, 'school.attendance.period.load')
    } finally {
      loading.value = false
    }
  }

  async function submitPeriodAttendance(
    periodNumber: number,
    date: string,
    entries: PeriodAttendanceEntry[],
  ): Promise<PeriodAttendanceSummary | null> {
    // 二重送信防止: 提出中の再呼び出しは API を叩かない
    if (submitting.value) return null
    submitting.value = true
    try {
      const summary = await api.submitPeriodAttendance(teamId.value, periodNumber, {
        attendanceDate: date,
        entries,
      })
      lastSummary.value = summary
      notifySuccess(t('school.attendance.period.submitSuccess'))
      return summary
    } catch (e) {
      handleApiError(e, 'school.attendance.period.submit')
      return null
    } finally {
      submitting.value = false
    }
  }

  return {
    candidates,
    loading,
    submitting,
    lastSummary,
    forbidden,
    loadCandidates,
    submitPeriodAttendance,
  }
}
