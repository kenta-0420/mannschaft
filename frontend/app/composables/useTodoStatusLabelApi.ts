import type {
  CreateTodoStatusLabelRequest,
  TodoStatusLabelListResponse,
  TodoStatusLabelResponse,
  UpdateTodoStatusLabelRequest,
} from '~/types/todoStatusLabel'

/**
 * カスタムステータスラベルのスコープ種別
 *  - me: 個人スコープ (`/api/v1/users/me/todo-status-labels`)
 *  - team: チームスコープ (`/api/v1/teams/{id}/todo-status-labels`)
 *  - organization: 組織スコープ (`/api/v1/organizations/{id}/todo-status-labels`)
 */
export type TodoStatusLabelScope = 'me' | 'team' | 'organization'

/**
 * F02.3.1 — TODO カスタムステータスラベル CRUD API
 *
 * バックエンド設計書: docs/features/F02.3.1_todo_status_labels_and_handoff.md §4
 */
export function useTodoStatusLabelApi() {
  const api = useApi()

  function buildBase(scope: TodoStatusLabelScope, scopeId?: string): string {
    if (scope === 'me') {
      return '/api/v1/users/me/todo-status-labels'
    }
    if (scope === 'team') {
      if (!scopeId) {
        throw new Error('team scope requires scopeId')
      }
      return `/api/v1/teams/${scopeId}/todo-status-labels`
    }
    if (!scopeId) {
      throw new Error('organization scope requires scopeId')
    }
    return `/api/v1/organizations/${scopeId}/todo-status-labels`
  }

  /**
   * ラベル一覧取得（SYSTEM 既定 3 種 + スコープ独自を含む）
   */
  async function listLabels(scope: TodoStatusLabelScope, scopeId?: string) {
    return api<TodoStatusLabelListResponse>(buildBase(scope, scopeId))
  }

  /**
   * ラベル作成（個人=本人 / チーム・組織=ADMIN のみ）
   */
  async function createLabel(
    scope: TodoStatusLabelScope,
    scopeId: string | undefined,
    body: CreateTodoStatusLabelRequest,
  ) {
    return api<TodoStatusLabelResponse>(buildBase(scope, scopeId), {
      method: 'POST',
      body,
    })
  }

  /**
   * ラベル更新（SYSTEM ラベルは 403 SYSTEM_LABEL_IMMUTABLE）
   */
  async function updateLabel(
    scope: TodoStatusLabelScope,
    scopeId: string | undefined,
    labelId: number,
    body: UpdateTodoStatusLabelRequest,
  ) {
    return api<TodoStatusLabelResponse>(`${buildBase(scope, scopeId)}/${labelId}`, {
      method: 'PUT',
      body,
    })
  }

  /**
   * ラベル削除（使用中の場合は 409 LABEL_IN_USE / SYSTEM ラベルは 403）
   */
  async function deleteLabel(
    scope: TodoStatusLabelScope,
    scopeId: string | undefined,
    labelId: number,
  ) {
    return api(`${buildBase(scope, scopeId)}/${labelId}`, { method: 'DELETE' })
  }

  return {
    listLabels,
    createLabel,
    updateLabel,
    deleteLabel,
  }
}
