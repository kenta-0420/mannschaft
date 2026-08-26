/** `/api/v1/me/teams` の所属チーム1件（slug → 数値 teamId 解決に必要な最小フィールド）。 */
interface MyTeamItem {
  id: number
  slug: string
}

/** `/api/v1/me/organizations` の所属組織1件（slug → 数値 orgId 解決に必要な最小フィールド）。 */
interface MyOrganizationItem {
  id: number
  slug: string
}

/**
 * 活動記録のスコープ識別子（team/org の slug もしくは数値文字列）から **数値 DB id** を解決する。
 *
 * <p>活動記録の作成 API（{@code POST /api/v1/activities?scope_id=..}）は数値 DB id（Long）を要求するが、
 * 画面は URL slug を持つ。team は {@code GET /api/v1/me/teams}、org は {@code GET /api/v1/me/organizations}
 * （いずれも {@code id} が数値・{@code slug} 付き）を引いて当該スコープの数値 id を得る。</p>
 *
 * <p>数値文字列が渡された場合は API 往復を省いて {@code Number()} で数値化して返す。
 * 解決不能（未所属・不通）の場合は null を返す（呼び出し側で null ガード・症状は隠さない）。</p>
 */
export function useActivityScopeId() {
  const api = useApi()

  async function resolveScopeId(
    scopeType: 'TEAM' | 'ORGANIZATION',
    scopeRef: string,
  ): Promise<number | null> {
    const numeric = Number(scopeRef)
    if (Number.isInteger(numeric) && numeric > 0 && String(numeric) === scopeRef) {
      return numeric
    }
    try {
      if (scopeType === 'TEAM') {
        const res = await api<{ data: MyTeamItem[] }>('/api/v1/me/teams')
        const team = (res.data ?? []).find((tm) => tm.slug === scopeRef)
        return team ? team.id : null
      }
      const res = await api<{ data: MyOrganizationItem[] }>('/api/v1/me/organizations')
      const org = (res.data ?? []).find((o) => o.slug === scopeRef)
      return org ? org.id : null
    } catch {
      // 不通・未所属いずれも null に潰れる。取得失敗を沈黙させないためログに残す（呼び出し側は null ガード）。
      console.warn('[useActivityScopeId] スコープID解決に失敗しました', { scopeType, scopeRef })
      return null
    }
  }

  return { resolveScopeId }
}
