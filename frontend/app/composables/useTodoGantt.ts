import type { GanttResponse } from '~/types/todo'

export function useTodoGantt() {
  const api = useApi()

  function buildBase(scopeType: 'team' | 'organization', scopeId: number) {
    return scopeType === 'team'
      ? `/api/v1/teams/${scopeId}`
      : `/api/v1/organizations/${scopeId}`
  }

  /**
   * 個人ガントビュー用 TODO 一覧を取得する
   * GET /api/v1/todos/gantt?from=yyyy-MM-dd&to=yyyy-MM-dd
   */
  async function getPersonalGanttTodos(from: string, to: string): Promise<GanttResponse> {
    return api<GanttResponse>(`/api/v1/todos/gantt?from=${from}&to=${to}`)
  }

  /**
   * チーム/組織ガントビュー用 TODO 一覧を取得する
   * GET /api/v1/teams/{teamId}/todos/gantt?from=yyyy-MM-dd&to=yyyy-MM-dd
   * GET /api/v1/organizations/{orgId}/todos/gantt?from=yyyy-MM-dd&to=yyyy-MM-dd
   */
  async function getGanttTodos(
    scopeType: 'team' | 'organization',
    scopeId: number,
    from: string,
    to: string,
  ): Promise<GanttResponse> {
    return api<GanttResponse>(
      `${buildBase(scopeType, scopeId)}/todos/gantt?from=${from}&to=${to}`,
    )
  }

  return {
    getPersonalGanttTodos,
    getGanttTodos,
  }
}
