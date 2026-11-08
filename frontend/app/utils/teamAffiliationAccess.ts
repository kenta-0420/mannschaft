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
  /** 対象チームでの実効権限名（/me/permissions の permissions）。roleName（SYSTEM_ADMIN を優先して返す）には依存しない。 */
  permissions: string[]
}

/**
 * チームの加盟操作者か（申請・取下げ・招待の承諾/辞退・離脱・招待制限の解除）。
 *
 * - チーム ADMIN 割当（isScopeAdmin）は常に操作者
 * - それ以外は、対象チームで MANAGE_ORG_AFFILIATION を保有しているときだけ（BE の TeamAffiliationAccessGuard は
 *   権限の保有で判定する。SYSTEM_ADMIN との兼任でも、権限を持っていれば操作者）
 * - SYSTEM_ADMIN 単独（ADMIN 割当も権限も無い）は、権限が無いので操作者ではない
 * - 管理者レンズ OFF のときは操作者として扱わない（タブを隠す。設計書 §13）
 */
export function canManageOrgAffiliation(input: OrgAffiliationAccessInput): boolean {
  if (!input.adminLens) return false
  if (input.isScopeAdmin) return true
  return input.permissions.includes(MANAGE_ORG_AFFILIATION)
}

/**
 * 権限グループ画面・メンバー一覧の割当を出してよいか（チーム ADMIN 割当・管理レンズ ON のみ。設計書 §3.2）。
 * SYSTEM_ADMIN を含む isAdmin ではなく isScopeAdmin で判定する（ADMIN 割当の無い運営者は一覧 API が 403）。
 */
export function canManagePermissionGroups(input: { adminLens: boolean, isScopeAdmin: boolean }): boolean {
  return input.adminLens && input.isScopeAdmin
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
