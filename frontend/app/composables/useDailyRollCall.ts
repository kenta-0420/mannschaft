import { ref, type Ref } from 'vue'
import type {
  DailyAttendanceResponse,
  DailyRollCallEntry,
  DailyRollCallSummary,
} from '~/types/school'

export function useDailyRollCall(teamId: Ref<number>) {
  const api = useDailyRollCallApi()
  const { error: notifyError, success: notifySuccess } = useNotification()
  const { t } = useI18n()

  const records = ref<DailyAttendanceResponse[]>([])
  const loading = ref(false)
  const submitting = ref(false)
  const lastSummary = ref<DailyRollCallSummary | null>(null)

  async function loadRecords(date: string): Promise<void> {
    loading.value = true
    try {
      const res = await api.getDailyAttendance(teamId.value, date)
      records.value = res.records
    } catch {
      notifyError(t('school.attendance.dailyRollCall.title'))
    } finally {
      loading.value = false
    }
  }

  async function submitRollCall(
    date: string,
    entries: DailyRollCallEntry[],
  ): Promise<DailyRollCallSummary | null> {
    submitting.value = true
    try {
      const summary = await api.submitRollCall(teamId.value, {
        attendanceDate: date,
        entries,
      })
      lastSummary.value = summary
      notifySuccess(t('school.attendance.dailyRollCall.submitSuccess'))
      return summary
    } catch {
      notifyError(t('school.attendance.dailyRollCall.title'))
      return null
    } finally {
      submitting.value = false
    }
  }

  return {
    records,
    loading,
    submitting,
    lastSummary,
    loadRecords,
    submitRollCall,
  }
}
