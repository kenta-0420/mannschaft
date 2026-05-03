import { ref } from 'vue'
import type { AtRiskStudentResponse, EvaluationStatus } from '~/types/school'

export function useAttendanceEvaluation() {
  const evaluationApi = useAttendanceEvaluationApi()
  const { success, error: notifyError } = useNotification()
  const { t } = useI18n()

  const atRiskStudents = ref<AtRiskStudentResponse[]>([])
  const loading = ref(false)
  const evaluating = ref(false)
  const resolving = ref(false)
  const statusFilter = ref<EvaluationStatus[]>([])

  async function loadAtRiskStudents(teamId: number): Promise<void> {
    loading.value = true
    try {
      atRiskStudents.value = await evaluationApi.getAtRiskStudents(
        teamId,
        statusFilter.value.length > 0 ? statusFilter.value : undefined,
      )
    } catch {
      notifyError(t('school.requirements.loadError'))
    } finally {
      loading.value = false
    }
  }

  async function runEvaluation(studentId: number, ruleId: number, teamId: number): Promise<void> {
    evaluating.value = true
    try {
      await evaluationApi.evaluateStudent(studentId, ruleId)
      success(t('school.requirements.evaluationSuccess'))
      await loadAtRiskStudents(teamId)
    } catch {
      notifyError(t('school.requirements.loadError'))
    } finally {
      evaluating.value = false
    }
  }

  async function resolveViolation(evaluationId: number, note: string, teamId: number): Promise<void> {
    resolving.value = true
    try {
      await evaluationApi.resolveViolation(evaluationId, { resolutionNote: note })
      success(t('school.requirements.resolveSuccess'))
      await loadAtRiskStudents(teamId)
    } catch {
      notifyError(t('school.requirements.loadError'))
    } finally {
      resolving.value = false
    }
  }

  return {
    atRiskStudents,
    loading,
    evaluating,
    resolving,
    statusFilter,
    loadAtRiskStudents,
    runEvaluation,
    resolveViolation,
  }
}
