import type { LinkScheduleRequest } from '~/types/todo'

export function useTodoScheduleLink() {
  const api = useApi()

  function buildBase(scopeType: 'team' | 'organization', scopeId: string) {
    return scopeType === 'team'
      ? `/api/v1/teams/${scopeId}`
      : `/api/v1/organizations/${scopeId}`
  }

  /**
   * スケジュールを TODO に連携する
   * POST /api/v1/teams/{teamId}/todos/{todoId}/link-schedule
   */
  async function linkSchedule(
    scopeType: 'team' | 'organization',
    scopeId: string,
    todoId: number,
    body: LinkScheduleRequest,
  ) {
    return api(`${buildBase(scopeType, scopeId)}/todos/${todoId}/link-schedule`, {
      method: 'POST',
      body,
    })
  }

  /**
   * TODO に連携されたスケジュールを解除する
   * DELETE /api/v1/teams/{teamId}/todos/{todoId}/link-schedule
   */
  async function unlinkSchedule(
    scopeType: 'team' | 'organization',
    scopeId: string,
    todoId: number,
  ) {
    return api(`${buildBase(scopeType, scopeId)}/todos/${todoId}/link-schedule`, {
      method: 'DELETE',
    })
  }

  return {
    linkSchedule,
    unlinkSchedule,
  }
}
