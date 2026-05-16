export function useShiftAutoAssignApi() {
  const api = useApi()
  const BASE = '/api/v1/shifts/schedules'

  async function runAutoAssign(
    scheduleId: number,
    body: { strategy: string; parameters?: Record<string, unknown> },
  ) {
    return api<{ data: unknown }>(`${BASE}/${scheduleId}/auto-assign`, {
      method: 'POST',
      body,
    })
  }

  async function confirmAutoAssign(
    scheduleId: number,
    req: { runId: number; assignmentIds: number[]; scheduleVersion: number },
  ) {
    return api<{ data: unknown }>(`${BASE}/${scheduleId}/auto-assign/confirm`, {
      method: 'POST',
      body: req,
    })
  }

  async function revokeAutoAssign(scheduleId: number) {
    return api(`${BASE}/${scheduleId}/auto-assign`, { method: 'DELETE' })
  }

  async function getAssignmentRuns(scheduleId: number) {
    return api<{ data: unknown[] }>(`${BASE}/${scheduleId}/assignment-runs`)
  }

  async function getAssignmentRunDetail(runId: number) {
    return api<{ data: unknown }>(`${BASE}/assignment-runs/${runId}`)
  }

  async function confirmVisualReview(runId: number, note?: string) {
    return api(`${BASE}/assignment-runs/${runId}/confirm-visual-review`, {
      method: 'POST',
      body: { note },
    })
  }

  return {
    runAutoAssign,
    confirmAutoAssign,
    revokeAutoAssign,
    getAssignmentRuns,
    getAssignmentRunDetail,
    confirmVisualReview,
  }
}
