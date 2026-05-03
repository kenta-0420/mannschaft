import { ref } from 'vue'
import type {
  StudentSummaryResponse,
  ClassSummaryListResponse,
  RecalculateSummaryRequest,
} from '~/types/school'

export function useAttendanceSummary() {
  const summaryApi = useAttendanceSummaryApi()
  const { success, error: notifyError } = useNotification()
  const { t } = useI18n()

  const studentSummary = ref<StudentSummaryResponse | null>(null)
  const classSummaries = ref<ClassSummaryListResponse | null>(null)
  const loading = ref(false)
  const recalculating = ref(false)

  async function loadStudentSummary(
    studentId: number,
    teamId: number,
    academicYear: number,
    termId?: number,
  ): Promise<void> {
    loading.value = true
    try {
      studentSummary.value = await summaryApi.getStudentSummary(studentId, teamId, academicYear, termId)
    } catch {
      notifyError(t('school.attendanceSummary.title'))
    } finally {
      loading.value = false
    }
  }

  async function loadClassSummaries(teamId: number, academicYear: number, termId?: number): Promise<void> {
    loading.value = true
    try {
      classSummaries.value = await summaryApi.getClassSummaries(teamId, academicYear, termId)
    } catch {
      notifyError(t('school.attendanceSummary.title'))
    } finally {
      loading.value = false
    }
  }

  async function recalculate(studentId: number, req: RecalculateSummaryRequest): Promise<void> {
    recalculating.value = true
    try {
      const result = await summaryApi.recalculate(studentId, req)
      studentSummary.value = result.summary
      success(t('school.attendanceSummary.recalculateSuccess'))
    } catch {
      notifyError(t('school.attendanceSummary.title'))
    } finally {
      recalculating.value = false
    }
  }

  return {
    studentSummary,
    classSummaries,
    loading,
    recalculating,
    loadStudentSummary,
    loadClassSummaries,
    recalculate,
  }
}
