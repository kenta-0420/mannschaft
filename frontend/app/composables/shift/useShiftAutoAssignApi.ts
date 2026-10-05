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

  // BE は必須の @RequestBody Long runId（オブジェクトではなく素の JSON 数値）を要求する。
  // ofetch は数値ボディも JSON 化できるが、送信形式（素の数値）と Content-Type を
  // 呼び出し側で明示して曖昧さを無くすため、JSON.stringify と header を指定している。
  async function revokeAutoAssign(scheduleId: number, runId: number) {
    return api(`${BASE}/${scheduleId}/auto-assign`, {
      method: 'DELETE',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(runId),
    })
  }

  async function getAssignmentRuns(scheduleId: number) {
    return api<{ data: unknown[] }>(`${BASE}/${scheduleId}/assignment-runs`)
  }

  async function getAssignmentRunDetail(runId: number) {
    return api<{ data: unknown }>(`/api/v1/shifts/assignment-runs/${runId}`)
  }

  async function confirmVisualReview(runId: number, note?: string) {
    return api(`/api/v1/shifts/assignment-runs/${runId}/confirm-visual-review`, {
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
