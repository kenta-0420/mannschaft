/**
 * 個人ダッシュボード向け「要対応」一覧取得 composable。
 *
 * GET /api/v1/dashboard/action-required を呼び出し、全スコープの要対応アイテムを返す。
 * レスポンスの snake_case を camelCase に変換して返す。
 *
 * 設計書: docs/features/F22.1_swipe_scope_dashboard/04_widgets.md §5
 */

// API レスポンスの snake_case 型
interface RawActionItem {
  item_type: string
  scope_type: string
  scope_id: number
  scope_slug: string
  scope_name: string
  item_id: string
  title: string
  deadline: string | null
  starts_at: string | null
}

interface RawActionRequiredResponse {
  items: RawActionItem[]
  total_count: number
}

// camelCase に変換した型
export interface PersonalActionItem {
  itemType: string // 'CIRCULATION' | 'SURVEY' | 'ATTENDANCE'
  scopeType: 'TEAM' | 'ORGANIZATION'
  scopeId: number
  scopeSlug: string
  scopeName: string
  itemId: string
  title: string
  deadline: string | null
  startsAt: string | null
}

export function usePersonalActionRequired() {
  const api = useApi()

  async function fetchActionRequired(): Promise<{ items: PersonalActionItem[]; totalCount: number }> {
    const res = await api<{ data: RawActionRequiredResponse }>(
      '/api/v1/dashboard/action-required',
    )
    return {
      items: res.data.items.map(i => ({
        itemType: i.item_type,
        scopeType: i.scope_type as 'TEAM' | 'ORGANIZATION',
        scopeId: i.scope_id,
        scopeSlug: i.scope_slug,
        scopeName: i.scope_name,
        itemId: i.item_id,
        title: i.title,
        deadline: i.deadline,
        startsAt: i.starts_at,
      })),
      totalCount: res.data.total_count,
    }
  }

  return { fetchActionRequired }
}
