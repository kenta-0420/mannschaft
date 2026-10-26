/**
 * F01.2.1 8-D — チームの「所属組織」画面（/teams/{slug}/affiliations）の view 切替。
 *
 * 切替はクエリ `view` で行い、子ルートは作らない（永続シェル配下の兄弟ルートの罠を避ける。設計書 §13）。
 * `organizations`（所属組織）は既定で、クエリを持たない。通知のリンク先は
 * `?view=invites`（招待・AC-L06）等で各 view に直接着地する。
 */
export const TEAM_AFFILIATIONS_VIEWS = ['organizations', 'invites', 'applications', 'restrictions'] as const

export type TeamAffiliationsView = (typeof TEAM_AFFILIATIONS_VIEWS)[number]

function firstValue(raw: unknown): unknown {
  return Array.isArray(raw) ? raw[0] : raw
}

/** クエリの `view` から実際に表示する view を決める。未指定・未知の値は所属組織。 */
export function resolveTeamAffiliationsView(raw: unknown): TeamAffiliationsView {
  const value = firstValue(raw)
  if (typeof value !== 'string') return 'organizations'
  return (TEAM_AFFILIATIONS_VIEWS as readonly string[]).includes(value)
    ? (value as TeamAffiliationsView)
    : 'organizations'
}

/** クエリに `view` が付いているか。 */
export function hasTeamAffiliationsViewQuery(raw: unknown): boolean {
  const value = firstValue(raw)
  return value !== undefined && value !== null && value !== ''
}
