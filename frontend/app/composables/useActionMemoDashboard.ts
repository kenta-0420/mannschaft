import type { ActionMemo, ActionMemoListResponse } from '~/types/actionMemo'

/**
 * F02.5 Phase 4-β 管理職ダッシュボード用コンポーザブル。
 *
 * チームメンバーの WORK メモ一覧をカーソルページネーションで取得する。
 * 認可チェックはバックエンド側で行う（呼び出し者が ADMIN/DEPUTY_ADMIN でなければ 403）。
 */
export function useActionMemoDashboard() {
  const { fetchMemberMemos } = useActionMemoApi()

  const memos = ref<ActionMemo[]>([])
  const nextCursor = ref<string | null>(null)
  const loading = ref(false)
  const loadingMore = ref(false)
  const error = ref<string | null>(null)

  async function loadMemos(teamId: number, memberId: number): Promise<void> {
    loading.value = true
    error.value = null
    memos.value = []
    nextCursor.value = null
    try {
      const res: ActionMemoListResponse = await fetchMemberMemos(teamId, memberId)
      memos.value = res.data
      nextCursor.value = res.nextCursor
    } catch {
      error.value = 'action_memo.dashboard.error_load'
    } finally {
      loading.value = false
    }
  }

  async function loadMore(teamId: number, memberId: number): Promise<void> {
    if (!nextCursor.value || loadingMore.value) return
    loadingMore.value = true
    try {
      const res: ActionMemoListResponse = await fetchMemberMemos(teamId, memberId, {
        cursor: nextCursor.value,
      })
      memos.value = [...memos.value, ...res.data]
      nextCursor.value = res.nextCursor
    } catch {
      error.value = 'action_memo.dashboard.error_load'
    } finally {
      loadingMore.value = false
    }
  }

  function reset(): void {
    memos.value = []
    nextCursor.value = null
    error.value = null
    loading.value = false
    loadingMore.value = false
  }

  return {
    memos,
    nextCursor,
    loading,
    loadingMore,
    error,
    loadMemos,
    loadMore,
    reset,
  }
}
