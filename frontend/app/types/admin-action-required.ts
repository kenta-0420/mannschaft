/**
 * F10.1.1 / P2b — 管理者向け横断「承認待ち」集約 API の FE 受信型（camelCase）。
 *
 * 設計書: docs/features/F10.1.1_team_org_admin_console/02_admin_lens_widgets.md §6
 *        / 03_admin_action_required_api.md §3
 *
 * API レスポンスは snake_case（03 §3.5）。`useScopeTabApi#getAdminActionRequired` が
 * API 境界で snake_case → camelCase へ正規化し、本型で消費する。
 *
 * メンバー向け F22.1 の `ActionRequiredSummary`（types/dashboard-scope.ts・「私が回答/確認すべきこと」）
 * とは**別物**であり、命名衝突を避けるため接頭辞 `Admin` を付けた `AdminActionRequiredSummary` とする。
 */

/** 集約ドメイン enum。team=RESERVATION/SHIFT_REQUEST/MATCHING、org=PAYMENT（03 §3.2）。 */
export type AdminActionDomain = 'RESERVATION' | 'SHIFT_REQUEST' | 'MATCHING' | 'PAYMENT'

/** 承認待ちアイテムのプレビュー要素（preview_size 件まで）。 */
export interface AdminActionItem {
  /** 対象ドメインの主キーを文字列化（BE は BIGINT/UUID を文字列で返す）。 */
  id: string
  title: string
  /** 申請者の表示名（requested_by）。 */
  requestedBy: string
  /** 申請日時 ISO8601（requested_at）。 */
  requestedAt: string
  /** その 1 件の個別遷移先ルート（detail_route・BE がスラッグ解決済み）。 */
  detailRoute: string
}

/** ドメイン別の承認待ちセクション。 */
export interface AdminActionDomainSummary {
  domain: AdminActionDomain
  /** 承認待ち件数（pending_count）。degraded=true のときは 0。 */
  pendingCount: number
  /** 集計失敗フラグ（degraded・一時障害時のみ true）。0 件と区別する（03 §4.3）。 */
  degraded: boolean
  /** 一覧ルート（list_route・BE がスラッグ解決済み）。 */
  listRoute: string
  items: AdminActionItem[]
}

/** 管理者向け横断承認待ち集約サマリ。 */
export interface AdminActionRequiredSummary {
  /** スコープ種別（scope_type）。 */
  scopeType: 'TEAM' | 'ORGANIZATION'
  /** スコープ ID（scope_id・内部 BIGINT）。 */
  scopeId: number
  /** 有効全ドメインの pending_count 合計（total_pending）。degraded ドメインは加算されない（03 §3.3）。 */
  totalPending: number
  /** 当該スコープで有効なドメインのみ（03 §3.2）。 */
  domains: AdminActionDomainSummary[]
}
