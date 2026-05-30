/**
 * F22.1 横スワイプ・スコープダッシュボード — タグ API composable
 *
 * 設計書: docs/features/F22.1_swipe_scope_dashboard/03_security_ux.md §2.3
 */
import type {
  ScopeTabType,
  ScopeTabPage,
  ScopeTabOrderUpdate,
  ActionRequiredSummary,
} from '~/types/dashboard-scope'

/**
 * スコープタブ（タグ行）API の composable。
 *
 * - getScopeTabs: GET /api/v1/dashboard/scope-tabs（表示順適用済みの所属スコープ一覧）
 * - updateOrder: PUT /api/v1/dashboard/scope-tabs/order（タグ表示順の一括更新）
 * - getActionRequired: GET /api/v1/dashboard/{team|organization}/{id}/action-required
 */
export function useScopeTabApi() {
  const api = useApi()

  /**
   * 所属スコープの一覧を 6 件/ページで取得する。
   *
   * @param scopeType - TEAM / ORGANIZATION
   * @param page - 0 始まりのページ番号（デフォルト 0）
   * @param folderId - F15.3 フォルダ ID（指定時は当該フォルダに絞り込み）。
   *   `my_scope_folders.id` は数値（BIGINT）であり BE 実装（Long）と揃えるため number。
   *   URL クエリへは String(folderId) で付与する。
   */
  async function getScopeTabs(
    scopeType: ScopeTabType,
    page = 0,
    folderId?: number,
  ): Promise<ScopeTabPage> {
    const q = new URLSearchParams({ scopeType, page: String(page) })
    if (folderId !== undefined && folderId !== null) q.set('folderId', String(folderId))
    const res = await api<{ data: ScopeTabPage }>(`/api/v1/dashboard/scope-tabs?${q}`)
    return res.data
  }

  /**
   * タグの表示順を一括更新する（UPSERT）。
   * リクエスト内に非所属の scopeId が 1 件でも含まれると 403 SCOPE_TAB_001 が返る。
   *
   * @param body - scopeType と orders 配列
   */
  async function updateOrder(body: ScopeTabOrderUpdate): Promise<void> {
    await api('/api/v1/dashboard/scope-tabs/order', { method: 'PUT', body })
  }

  /**
   * 統合「要対応」（回覧板/アンケート/出欠）の集計を取得する。
   *
   * @param scopeType - TEAM / ORGANIZATION
   * @param scopeId - チーム ID または組織 ID
   */
  async function getActionRequired(
    scopeType: ScopeTabType,
    scopeId: number,
  ): Promise<ActionRequiredSummary> {
    const base = scopeType === 'TEAM' ? `team/${scopeId}` : `organization/${scopeId}`
    const res = await api<{ data: ActionRequiredSummary }>(
      `/api/v1/dashboard/${base}/action-required`,
    )
    return res.data
  }

  return { getScopeTabs, updateOrder, getActionRequired }
}
