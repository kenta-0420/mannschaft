import { ref, type Ref } from 'vue'
import type { MonthlyStatisticsResponse, StudentTermStatisticsResponse } from '~/types/school'

export function useAttendanceStatistics(teamId: Ref<number>) {
  const api = useAttendanceStatisticsApi()
  const { error: notifyError } = useNotification()
  const { t } = useI18n()

  const monthlyStats = ref<MonthlyStatisticsResponse | null>(null)
  const termStats = ref<StudentTermStatisticsResponse | null>(null)
  const loadingMonthly = ref(false)
  const loadingTerm = ref(false)
  const exporting = ref(false)

  async function loadMonthlyStatistics(year: number, month: number): Promise<void> {
    loadingMonthly.value = true
    try {
      monthlyStats.value = await api.getMonthlyStatistics(teamId.value, year, month)
    } catch {
      notifyError(t('school.statistics.title'))
    } finally {
      loadingMonthly.value = false
    }
  }

  async function loadTermStatistics(from: string, to: string): Promise<void> {
    loadingTerm.value = true
    try {
      termStats.value = await api.getTermStatistics(teamId.value, from, to)
    } catch {
      notifyError(t('school.statistics.title'))
    } finally {
      loadingTerm.value = false
    }
  }

  function downloadCsv(from: string, to: string): void {
    exporting.value = true
    try {
      api.exportCsv(teamId.value, from, to)
    } finally {
      exporting.value = false
    }
  }

  return {
    monthlyStats,
    termStats,
    loadingMonthly,
    loadingTerm,
    exporting,
    loadMonthlyStatistics,
    loadTermStatistics,
    downloadCsv,
  }
}
