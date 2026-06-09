/**
 * F08.10 組織／チーム ID 解決の共通 composable（04_frontend_and_ux.md §G.4）。
 *
 * ## 何を解決するか（識別子契約の根治）
 * teams/orgs の URL 正準は UUID publicId（`/teams/[id]` の [id]=publicId 文字列）だが、
 * F08.10 の単独試合 REST API は `/organizations/{orgId}/teams/{teamId}/...` 配下で
 * orgId/teamId が **数値** である（match BE は数値のまま正しい）。
 *
 * 旧実装は `getOrganizations(teamPublicId)` の戻り org id（UUID）を `typeof === 'number'` で
 * 判定して常に null を返し、加えて各ページが `Number(teamPublicId)`（UUID→NaN）で二段目の
 * 地雷を踏んでいた。本 composable は **publicId(UUID) → 数値 orgId ＋ 数値 teamId** を
 * 一括解決して `{ orgId, teamId }` で返し、呼び出し側の `Number(uuid)` を不要にする。
 *
 * ## 数値 id の入手源（単一 API・単一往復）
 *   - `GET /me/teams`（MyTeamResponse: `id`数値 ＋ `publicId` ＋ `organizationId`数値）
 *       → publicId 一致で当該チームの **数値 teamId** と **親組織の数値 orgId** を同時に得る。
 *
 * ## 親組織の数値 orgId について（家老懸念2 への回答）
 * BE `MyTeamResponse.organizationId` は当該チームの ACTIVE な親組織 ID を 1 件返す
 * （MeController が F00 ScopeAncestorResolver と同じ `findOrganizationIdByTeamIdIn` を再利用）。
 * 1 チームが複数組織に所属し得るが、その場合も「当該チームの親組織」のうち 1 件に一意化される。
 * `/teams/{publicId}/organizations`（UUID のみ）や `/me/organizations`（ユーザーが親組織の
 * 直接メンバーでない場合に欠落する）に依存せず、チーム所属だけで数値 orgId を得られる。
 *
 * ## キャッシュ方式
 * teamPublicId をキーにした Map に解決済みコンテキストを保持する。複数チーム所属ユーザーが
 * チームを切り替えても、別 publicId は必ず新たに解決され、最初のチームの値を返し続ける
 * バグ（旧 Phase3-C 検分の指摘）は起きない。解決不能時は null を返し、握り潰さず通知する。
 */

/** 数値 orgId ＋ 数値 teamId の解決結果。 */
export interface MatchOrgContext {
  orgId: number
  teamId: number
}

interface MyTeamItem {
  id: number
  publicId: string
  /** 親組織の数値 ID（BE MyTeamResponse.organizationId・null 許容）。 */
  organizationId: number | null
}

export function useMatchOrgContext() {
  const api = useApi()
  const notification = useNotification()
  const { t } = useI18n()

  /** teamPublicId → 解決済みコンテキスト（同一チームの API 重複呼び出しを防ぐ）。 */
  const contextCache = new Map<string, MatchOrgContext>()

  /**
   * teamPublicId（UUID 文字列）から数値 orgId ＋ 数値 teamId を解決する。
   * 同一 teamPublicId が既にキャッシュ済みなら API を再度叩かずキャッシュ値を返す。
   * 別の teamPublicId は必ず新たに解決する。解決できなかった場合は null を返す
   * （呼び出し側で null ガードする。症状は隠さずトーストで通知する）。
   */
  async function resolveContext(teamPublicId: string): Promise<MatchOrgContext | null> {
    const cached = contextCache.get(teamPublicId)
    if (cached !== undefined) return cached

    try {
      const res = await api<{ data: MyTeamItem[] }>('/api/v1/me/teams')
      const myTeam = (res.data ?? []).find((tm) => tm.publicId === teamPublicId)
      if (!myTeam || typeof myTeam.organizationId !== 'number') {
        // チーム未所属 or 親組織未解決（試合 API は親組織コンテキスト必須）。
        notification.warn(t('match.org_context.resolve_failed'))
        return null
      }

      const ctx: MatchOrgContext = { orgId: myTeam.organizationId, teamId: myTeam.id }
      contextCache.set(teamPublicId, ctx)
      return ctx
    } catch {
      notification.warn(t('match.org_context.resolve_failed'))
      return null
    }
  }

  return {
    resolveContext,
  }
}
