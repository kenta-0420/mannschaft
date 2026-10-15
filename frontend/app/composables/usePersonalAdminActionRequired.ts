/**
 * 個人ダッシュボード向け「承認待ち」横断集約取得 composable（司令塔第二弾）。
 *
 * GET /api/v1/dashboard/admin-action-required を呼び出し、ユーザーが ADMIN/DEPUTY_ADMIN として
 * 管理する全スコープの承認待ちアイテム（予約承認/シフトリクエスト/マッチング応募/未収請求）を返す。
 * レスポンスの snake_case を camelCase に変換して返す。
 *
 * {@link usePersonalActionRequired}（「私が回答/確認すべきこと」・全メンバー向け）とは別 API・別型。
 *
 * 設計書: ADHD-UX戦役第四陣第二弾「承認待ち横断集約」
 */

// API レスポンスの snake_case 型
interface RawAdminActionItem {
  domain: string
  scope_type: string
  scope_id: number
  scope_slug: string
  scope_name: string
  item_id: string
  title: string
  requested_by: string
  requested_at: string | null
  detail_route: string
}

interface RawPersonalAdminActionRequiredResponse {
  items: RawAdminActionItem[]
  total_pending: number
}

// camelCase に変換した型
export interface PersonalAdminActionItem {
  domain: 'RESERVATION' | 'SHIFT_REQUEST' | 'MATCHING' | 'PAYMENT'
  scopeType: 'TEAM' | 'ORGANIZATION'
  scopeId: number
  scopeSlug: string
  scopeName: string
  itemId: string
  title: string
  requestedBy: string
  requestedAt: string | null
  detailRoute: string
}

export interface PersonalAdminActionRequiredResult {
  items: PersonalAdminActionItem[]
  totalPending: number
}

export function usePersonalAdminActionRequired() {
  const api = useApi()

  async function fetchAdminActionRequired(): Promise<PersonalAdminActionRequiredResult> {
    const res = await api<{ data: RawPersonalAdminActionRequiredResponse }>(
      '/api/v1/dashboard/admin-action-required',
    )
    return {
      items: res.data.items.map(i => ({
        domain: i.domain as PersonalAdminActionItem['domain'],
        scopeType: i.scope_type as 'TEAM' | 'ORGANIZATION',
        scopeId: i.scope_id,
        scopeSlug: i.scope_slug,
        scopeName: i.scope_name,
        itemId: i.item_id,
        title: i.title,
        requestedBy: i.requested_by,
        requestedAt: i.requested_at,
        detailRoute: i.detail_route,
      })),
      totalPending: res.data.total_pending,
    }
  }

  return { fetchAdminActionRequired }
}
