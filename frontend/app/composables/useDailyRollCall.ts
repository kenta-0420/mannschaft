import { ref, watch, type Ref } from 'vue'
import { isForbiddenError } from '~/composables/useAttendancePermissions'
import type {
  DailyAttendanceResponse,
  DailyRollCallEntry,
  DailyRollCallSummary,
} from '~/types/school'

export function useDailyRollCall(teamId: Ref<string>) {
  const api = useDailyRollCallApi()
  const { success: notifySuccess } = useNotification()
  const { handleApiError } = useErrorHandler()
  const { t } = useI18n()

  const records = ref<DailyAttendanceResponse[]>([])
  const loading = ref(false)
  const submitting = ref(false)
  const lastSummary = ref<DailyRollCallSummary | null>(null)
  // 閲覧が 403 のとき true。握りつぶさず画面で「権限がありません」を明示する（AC-18）
  const forbidden = ref(false)

  const loadFailed = ref(false)
  const loaded = ref(false)
  const recordKey = ref('')
  let sequence = 0
  function clear(): void {
    ++sequence
    records.value = []
    loaded.value = false
    recordKey.value = ''
    forbidden.value = false
    loadFailed.value = false
  }
  watch(teamId, () => {
    clear()
    loading.value = false
    lastSummary.value = null
  }, { flush: 'sync' })

  async function loadRecords(date: string): Promise<void> {
    clear()
    const seq = sequence
    const requestedTeam = teamId.value
    const current = () => seq === sequence && requestedTeam === teamId.value
    if (!requestedTeam) return
    loading.value = true
    try {
      const res = await api.getDailyAttendance(requestedTeam, date)
      if (!current()) return
      records.value = res.records
      loaded.value = true
      recordKey.value = `${requestedTeam}:${date}`
      forbidden.value = false
    } catch (e) {
      if (!current()) return
      if (isForbiddenError(e)) {
        forbidden.value = true
        return
      }
      loadFailed.value = true
      handleApiError(e, 'school.attendance.dailyRollCall.load')
    } finally {
      if (current()) loading.value = false
    }
  }

  async function submitRollCall(
    date: string,
    entries: DailyRollCallEntry[],
  ): Promise<DailyRollCallSummary | null> {
    // 二重送信防止: 提出中の再呼び出しは API を叩かない
    if (submitting.value) return null
    submitting.value = true
    const requestedTeam = teamId.value
    try {
      const summary = await api.submitRollCall(requestedTeam, {
        attendanceDate: date,
        entries,
      })
      if (teamId.value !== requestedTeam) return null
      lastSummary.value = summary
      notifySuccess(t('school.attendance.dailyRollCall.submitSuccess'))
      return summary
    } catch (e) {
      handleApiError(e, 'school.attendance.dailyRollCall.submit')
      return null
    } finally {
      submitting.value = false
    }
  }

  return {
    records,
    loadFailed,
    loaded,
    recordKey,
    loading,
    submitting,
    lastSummary,
    forbidden,
    loadRecords,
    submitRollCall,
  }
}
