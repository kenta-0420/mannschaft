/**
 * F01.2.1 8-B — 組織の「所属チーム」画面（member-teams）の view 切替。
 *
 * 切替はクエリ `view` で行い、子ルートは作らない（永続シェル配下の兄弟ルートの罠を避ける。設計書 §13）。
 * `teams` は一覧表示で、クエリを持たない（`view` なし）。
 */
export const MEMBER_TEAMS_MANAGE_VIEWS = ['applications', 'invites', 'restrictions', 'groups'] as const

export type MemberTeamsManageView = (typeof MEMBER_TEAMS_MANAGE_VIEWS)[number]
export type MemberTeamsView = 'teams' | MemberTeamsManageView

function isManageView(value: string): value is MemberTeamsManageView {
  return (MEMBER_TEAMS_MANAGE_VIEWS as readonly string[]).includes(value)
}

/**
 * クエリの `view` と権限から、実際に表示する view を決める。
 *
 * - 未指定・未知の値は一覧（`teams`）
 * - 管理系 view は組織 ADMIN かつ管理レンズ ON のときだけ。権限のない者が直打ちしたら一覧へ戻す（AC-K02）
 */
export function resolveMemberTeamsView(raw: unknown, canManage: boolean): MemberTeamsView {
  const value = Array.isArray(raw) ? raw[0] : raw
  if (typeof value !== 'string' || !isManageView(value)) return 'teams'
  return canManage ? value : 'teams'
}

/** グループ選択ドロップダウンで「未分類」を表す値（groupId=null を select の値として持てないため）。 */
export const UNASSIGNED_GROUP_VALUE = '__unassigned__'

/** 申請・招待・制限の日時表示。値が無い・不正なら空文字。 */
export function formatAffiliationDate(iso: string | undefined, locale: string): string {
  if (!iso) return ''
  const date = new Date(iso)
  return Number.isNaN(date.getTime()) ? '' : date.toLocaleString(locale)
}

/** クエリに `view` が付いているか（直打ちを一覧へ戻すとき、クエリを外す要否の判定に使う）。 */
export function hasViewQuery(raw: unknown): boolean {
  const value = Array.isArray(raw) ? raw[0] : raw
  return value !== undefined && value !== null && value !== ''
}
