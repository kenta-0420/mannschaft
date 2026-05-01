import { ref, type Ref } from 'vue'
import type {
  CandidateItem,
  PeriodAttendanceEntry,
  PeriodAttendanceSummary,
} from '~/types/school'

export function usePeriodAttendance(teamId: Ref<number>) {
  const api = usePeriodAttendanceApi()
  const { error: notifyError, success: notifySuccess } = useNotification()
  const { t } = useI18n()

  const candidates = ref<CandidateItem[]>([])
  const loading = ref(false)
  const submitting = ref(false)
  const lastSummary = ref<PeriodAttendanceSummary | null>(null)

  async function loadCandidates(periodNumber: number, date: string): Promise<void> {
    loading.value = true
    try {
      const res = await api.getPeriodCandidates(teamId.value, periodNumber, date)
      candidates.value = res.candidates
    } catch {
      notifyError(t('school.attendance.period.title'))
    } finally {
      loading.value = false
    }
  }

  async function submitPeriodAttendance(
    periodNumber: number,
    date: string,
    entries: PeriodAttendanceEntry[],
  ): Promise<PeriodAttendanceSummary | null> {
    submitting.value = true
    try {
      const summary = await api.submitPeriodAttendance(teamId.value, periodNumber, {
        attendanceDate: date,
        entries,
      })
      lastSummary.value = summary
      notifySuccess(t('school.attendance.period.submitSuccess'))
      return summary
    } catch {
      notifyError(t('school.attendance.period.title'))
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
    loadCandidates,
    submitPeriodAttendance,
  }
}
