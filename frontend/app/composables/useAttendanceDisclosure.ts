import { ref } from 'vue'
import type {
  DisclosureRequest,
  WithholdRequest,
  DisclosureResponse,
  DisclosedEvaluationResponse,
} from '~/types/school'

export function useAttendanceDisclosure() {
  const disclosureApi = useAttendanceDisclosureApi()
  const { success, error: notifyError } = useNotification()
  const { t } = useI18n()

  const disclosing = ref(false)
  const disclosureHistory = ref<DisclosureResponse[]>([])
  const myDisclosedEvaluations = ref<DisclosedEvaluationResponse[]>([])
  const loadingHistory = ref(false)
  const loadingInbox = ref(false)

  async function executeDisclose(
    teamId: number,
    evaluationId: number,
    req: DisclosureRequest,
    onSuccess?: () => void,
  ): Promise<void> {
    disclosing.value = true
    try {
      await disclosureApi.disclose(teamId, evaluationId, req)
      success(t('school.disclosure.submit'))
      onSuccess?.()
    } catch {
      notifyError(t('school.disclosure.submit'))
    } finally {
      disclosing.value = false
    }
  }

  async function executeWithhold(
    teamId: number,
    evaluationId: number,
    req: WithholdRequest,
    onSuccess?: () => void,
  ): Promise<void> {
    disclosing.value = true
    try {
      await disclosureApi.withhold(teamId, evaluationId, req)
      success(t('school.disclosure.submit'))
      onSuccess?.()
    } catch {
      notifyError(t('school.disclosure.submit'))
    } finally {
      disclosing.value = false
    }
  }

  async function loadDisclosureHistory(teamId: number, evaluationId: number): Promise<void> {
    loadingHistory.value = true
    try {
      disclosureHistory.value = await disclosureApi.getDisclosureHistory(teamId, evaluationId)
    } catch {
      notifyError(t('school.evaluation.loadError'))
    } finally {
      loadingHistory.value = false
    }
  }

  async function loadMyDisclosedEvaluations(): Promise<void> {
    loadingInbox.value = true
    try {
      myDisclosedEvaluations.value = await disclosureApi.getMyDisclosedEvaluations()
    } catch {
      notifyError(t('school.evaluation.loadError'))
    } finally {
      loadingInbox.value = false
    }
  }

  return {
    disclosing,
    disclosureHistory,
    myDisclosedEvaluations,
    loadingHistory,
    loadingInbox,
    executeDisclose,
    executeWithhold,
    loadDisclosureHistory,
    loadMyDisclosedEvaluations,
  }
}
