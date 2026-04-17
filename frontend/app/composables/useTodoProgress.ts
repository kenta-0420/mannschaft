import type { UpdateProgressRequest, UpdateProgressModeRequest } from '~/types/todo'

export function useTodoProgress() {
  const api = useApi()

  function buildBase(scopeType: 'team' | 'organization', scopeId: number) {
    return scopeType === 'team'
      ? `/api/v1/teams/${scopeId}`
      : `/api/v1/organizations/${scopeId}`
  }

  /**
   * 進捗率を手動更新する
   * PATCH /api/v1/teams/{teamId}/todos/{todoId}/progress
   */
  async function updateProgress(
    scopeType: 'team' | 'organization',
    scopeId: number,
    todoId: number,
    body: UpdateProgressRequest,
  ) {
    return api(`${buildBase(scopeType, scopeId)}/todos/${todoId}/progress`, {
      method: 'PATCH',
      body,
    })
  }

  /**
   * 進捗算出モード（手動/自動）を切り替える
   * PATCH /api/v1/teams/{teamId}/todos/{todoId}/progress-mode
   */
  async function updateProgressMode(
    scopeType: 'team' | 'organization',
    scopeId: number,
    todoId: number,
    body: UpdateProgressModeRequest,
  ) {
    return api(`${buildBase(scopeType, scopeId)}/todos/${todoId}/progress-mode`, {
      method: 'PATCH',
      body,
    })
  }

  return {
    updateProgress,
    updateProgressMode,
  }
}
