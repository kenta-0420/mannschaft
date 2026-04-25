import type {
  AssignmentRun,
  AssignmentParameters,
  AssignmentStrategyType,
} from '~/types/shift'

export function useAutoAssign(scheduleId: Ref<number>) {
  const shiftApi = useShiftApi()
  const runs = ref<AssignmentRun[]>([])
  const currentRun = ref<AssignmentRun | null>(null)
  const isRunning = ref(false)

  async function runAutoAssign(
    strategy: AssignmentStrategyType,
    params: AssignmentParameters,
  ): Promise<void> {
    isRunning.value = true
    try {
      const res = await shiftApi.runAutoAssign(scheduleId.value, {
        strategy,
        parameters: params as Record<string, unknown>,
      })
      const data = (res as { data: AssignmentRun }).data
      currentRun.value = data
      await fetchRuns()
    } finally {
      isRunning.value = false
    }
  }

  async function confirmAutoAssign(
    runId: number,
    assignmentIds: number[],
    scheduleVersion: number,
  ): Promise<void> {
    await shiftApi.confirmAutoAssign(scheduleId.value, { runId, assignmentIds, scheduleVersion })
    await fetchRuns()
    if (currentRun.value?.id === runId) {
      await fetchRunDetail(runId)
    }
  }

  async function revokeAutoAssign(): Promise<void> {
    await shiftApi.revokeAutoAssign(scheduleId.value)
    currentRun.value = null
    await fetchRuns()
  }

  async function fetchRuns(): Promise<void> {
    const res = await shiftApi.getAssignmentRuns(scheduleId.value)
    runs.value = (res as { data: AssignmentRun[] }).data
  }

  async function fetchRunDetail(runId: number): Promise<void> {
    const res = await shiftApi.getAssignmentRunDetail(runId)
    currentRun.value = (res as { data: AssignmentRun }).data
  }

  async function confirmVisualReview(runId: number, note?: string): Promise<void> {
    await shiftApi.confirmVisualReview(runId, note)
    await fetchRunDetail(runId)
    await fetchRuns()
  }

  return {
    runs,
    currentRun,
    isRunning,
    runAutoAssign,
    confirmAutoAssign,
    revokeAutoAssign,
    fetchRuns,
    fetchRunDetail,
    confirmVisualReview,
  }
}
