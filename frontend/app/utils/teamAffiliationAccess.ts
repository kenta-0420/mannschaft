/**
 * F01.2.1 8-D — チームシェルの「所属組織」「権限グループ」タブの表示・滞在判定（設計書 §3.2・§13）。
 *
 * 判定は純関数に切り出し、チームシェル（pages/teams/[slug].vue）と子ページが同じ規則を使う。
 */

/** チーム側の加盟操作権限（BE: TeamAffiliationAccessGuard が見る権限名）。 */
export const MANAGE_ORG_AFFILIATION = 'MANAGE_ORG_AFFILIATION'

export interface OrgAffiliationAccessInput {
  /** 管理者レンズ（true=管理者ビュー）。 */
  adminLens: boolean
  /** 対象チームの ADMIN 割当があるか（/me/permissions の scopeAdmin）。SYSTEM_ADMIN 単独は false。 */
  isScopeAdmin: boolean
  /** 自分の実効ロール名（SYSTEM_ADMIN を優先して返す）。 */
  roleName: string | null
  /** 自分の実効権限名。 */
  permissions: string[]
}

/**
 * チームの加盟操作者か（申請・取下げ・招待の承諾/辞退・離脱・招待制限の解除）。
 *
 * - チーム ADMIN 割当は常に操作者
 * - DEPUTY_ADMIN・MEMBER は MANAGE_ORG_AFFILIATION を付与されたときだけ
 * - SYSTEM_ADMIN 単独は操作者ではない（BE が 403 を返す）
 * - 管理者レンズ OFF のときは操作者として扱わない（タブを隠す。設計書 §13）
 */
export function canManageOrgAffiliation(input: OrgAffiliationAccessInput): boolean {
  if (!input.adminLens) return false
  if (input.isScopeAdmin) return true
  if (input.roleName === 'SYSTEM_ADMIN') return false
  return input.permissions.includes(MANAGE_ORG_AFFILIATION)
}

/** 権限グループ画面・メンバー一覧の割当を出してよいか（チーム ADMIN・管理レンズ ON のみ）。 */
export function canManagePermissionGroups(input: { adminLens: boolean, isAdmin: boolean }): boolean {
  return input.adminLens && input.isAdmin
}

export interface GuardedTabInput {
  /** 現在のタブ key。 */
  tab: string
  /** 権限の取得が終わって判定してよいか。 */
  ready: boolean
  canAffiliate: boolean
  canGroups: boolean
}

/**
 * 権限・レンズを失った状態で保護タブに滞在しているとき、ダッシュボードへ戻すべきか。
 * 権限の確定前（ready=false）は戻さない（取得前に弾いて、管理者を誤って追い返さない）。
 */
export function shouldLeaveGuardedTeamTab(input: GuardedTabInput): boolean {
  if (!input.ready) return false
  if (input.tab === 'affiliations') return !input.canAffiliate
  if (input.tab === 'permission-groups') return !input.canGroups
  return false
}
