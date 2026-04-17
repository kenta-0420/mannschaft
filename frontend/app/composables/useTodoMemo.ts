import type {
  SharedMemoListResponse,
  PersonalMemo,
  CreateSharedMemoRequest,
  UpdateSharedMemoRequest,
  UpsertPersonalMemoRequest,
} from '~/types/todo'

export function useTodoMemo() {
  const api = useApi()

  function buildBase(scopeType: 'team' | 'organization', scopeId: number) {
    return scopeType === 'team'
      ? `/api/v1/teams/${scopeId}`
      : `/api/v1/organizations/${scopeId}`
  }

  // === 共有メモ ===

  /**
   * 共有メモ一覧を取得する（ページネーション20件）
   * GET /api/v1/teams/{teamId}/todos/{todoId}/shared-memos
   */
  async function getSharedMemos(
    scopeType: 'team' | 'organization',
    scopeId: number,
    todoId: number,
    page: number = 0,
  ): Promise<SharedMemoListResponse> {
    return api<SharedMemoListResponse>(
      `${buildBase(scopeType, scopeId)}/todos/${todoId}/shared-memos?page=${page}&size=20`,
    )
  }

  /**
   * 共有メモを作成する（引用返信にも対応）
   * POST /api/v1/teams/{teamId}/todos/{todoId}/shared-memos
   */
  async function createSharedMemo(
    scopeType: 'team' | 'organization',
    scopeId: number,
    todoId: number,
    body: CreateSharedMemoRequest,
  ) {
    return api(`${buildBase(scopeType, scopeId)}/todos/${todoId}/shared-memos`, {
      method: 'POST',
      body,
    })
  }

  /**
   * 共有メモを更新する（本人のみ）
   * PUT /api/v1/teams/{teamId}/todos/{todoId}/shared-memos/{memoId}
   */
  async function updateSharedMemo(
    scopeType: 'team' | 'organization',
    scopeId: number,
    todoId: number,
    memoId: number,
    body: UpdateSharedMemoRequest,
  ) {
    return api(
      `${buildBase(scopeType, scopeId)}/todos/${todoId}/shared-memos/${memoId}`,
      { method: 'PUT', body },
    )
  }

  /**
   * 共有メモを削除する（本人 or ADMIN）
   * DELETE /api/v1/teams/{teamId}/todos/{todoId}/shared-memos/{memoId}
   */
  async function deleteSharedMemo(
    scopeType: 'team' | 'organization',
    scopeId: number,
    todoId: number,
    memoId: number,
  ) {
    return api(
      `${buildBase(scopeType, scopeId)}/todos/${todoId}/shared-memos/${memoId}`,
      { method: 'DELETE' },
    )
  }

  // === 個人メモ ===

  /**
   * 個人メモを取得する（自分のみ閲覧可能）
   * GET /api/v1/teams/{teamId}/todos/{todoId}/personal-memo
   */
  async function getPersonalMemo(
    scopeType: 'team' | 'organization',
    scopeId: number,
    todoId: number,
  ): Promise<{ data: PersonalMemo | null }> {
    return api<{ data: PersonalMemo | null }>(
      `${buildBase(scopeType, scopeId)}/todos/${todoId}/personal-memo`,
    )
  }

  /**
   * 個人メモを保存する（UPSERT）
   * PUT /api/v1/teams/{teamId}/todos/{todoId}/personal-memo
   */
  async function upsertPersonalMemo(
    scopeType: 'team' | 'organization',
    scopeId: number,
    todoId: number,
    body: UpsertPersonalMemoRequest,
  ) {
    return api(`${buildBase(scopeType, scopeId)}/todos/${todoId}/personal-memo`, {
      method: 'PUT',
      body,
    })
  }

  return {
    getSharedMemos,
    createSharedMemo,
    updateSharedMemo,
    deleteSharedMemo,
    getPersonalMemo,
    upsertPersonalMemo,
  }
}
