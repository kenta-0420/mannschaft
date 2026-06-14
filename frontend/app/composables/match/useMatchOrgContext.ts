/**
 * F08.10 組織／チーム ID 解決の共通 composable（04_frontend_and_ux.md §G.4）。
 *
 * ## 何を解決するか（識別子契約の根治）
 * teams/orgs の URL 正準は **slug**（`/teams/[slug]` の [slug]=slug 文字列）だが、
 * F08.10 の単独試合 REST API は `/organizations/{orgId}/teams/{teamId}/...` 配下で
 * orgId/teamId が **数値** である（match BE は数値のまま正しい）。
 *
 * 旧実装は `getOrganizations(teamSlug)` の戻り org id（UUID）を `typeof === 'number'` で
 * 判定して常に null を返し、加えて各ページが `Number(teamSlug)`（slug→NaN）で二段目の
 * 地雷を踏んでいた。本 composable は **slug → 数値 orgId ＋ 数値 teamId** を
 * 一括解決して `{ orgId, teamId }` で返し、呼び出し側の `Number(slug)` を不要にする。
 *
 * ## 数値 id の入手源（単一 API・単一往復）
 *   - `GET /me/teams`（MyTeamResponse: `id`数値 ＋ `slug` ＋ `organizationId`数値）
 *       → slug 一致（`tm.slug === teamSlug`）で当該チームの **数値 teamId** と **親組織の数値 orgId** を同時に得る。
 *
 * ## 親組織の数値 orgId について（家老懸念2 への回答）
 * BE `MyTeamResponse.organizationId` は当該チームの ACTIVE な親組織 ID を 1 件返す
 * （MeController が F00 ScopeAncestorResolver と同じ `findOrganizationIdByTeamIdIn` を再利用）。
 * 1 チームが複数組織に所属し得るが、その場合も「当該チームの親組織」のうち 1 件に一意化される。
 * `/teams/{slug}/organizations`（slug のみ）や `/me/organizations`（ユーザーが親組織の
 * 直接メンバーでない場合に欠落する）に依存せず、チーム所属だけで数値 orgId を得られる。
 *
 * ## キャッシュ方式
 * teamSlug をキーにした Map に解決済みコンテキストを保持する。複数チーム所属ユーザーが
 * チームを切り替えても、別 slug は必ず新たに解決され、最初のチームの値を返し続ける
 * バグ（旧 Phase3-C 検分の指摘）は起きない。解決不能時は null を返し、握り潰さず通知する。
 */

/** 数値 orgId ＋ 数値 teamId の解決結果。 */
export interface MatchOrgContext {
  orgId: number
  teamId: number
}

/**
 * 数値 teamId からの解決結果（入口①の大会対戦表用）。
 * live 画面の遷移先は `/teams/{teamSlug}/matches/...` のため slug も同時に返す。
 */
export interface MatchOrgContextByTeamId extends MatchOrgContext {
  teamSlug: string
}

interface MyTeamItem {
  id: number
  slug: string
  /** 親組織の数値 ID（BE MyTeamResponse.organizationId・null 許容）。 */
  organizationId: number | null
}

export function useMatchOrgContext() {
  const api = useApi()
  const notification = useNotification()
  const { t } = useI18n()

  /** teamSlug → 解決済みコンテキスト（同一チームの API 重複呼び出しを防ぐ）。 */
  const contextCache = new Map<string, MatchOrgContext>()

  /**
   * teamSlug（URL slug 文字列）から数値 orgId ＋ 数値 teamId を解決する。
   * 同一 teamSlug が既にキャッシュ済みなら API を再度叩かずキャッシュ値を返す。
   * 別の teamSlug は必ず新たに解決する。解決できなかった場合は null を返す
   * （呼び出し側で null ガードする。症状は隠さずトーストで通知する）。
   */
  async function resolveContext(teamSlug: string): Promise<MatchOrgContext | null> {
    const cached = contextCache.get(teamSlug)
    if (cached !== undefined) return cached

    try {
      const res = await api<{ data: MyTeamItem[] }>('/api/v1/me/teams')
      const myTeam = (res.data ?? []).find((tm) => tm.slug === teamSlug)
      if (!myTeam || typeof myTeam.organizationId !== 'number') {
        // チーム未所属 or 親組織未解決（試合 API は親組織コンテキスト必須）。
        notification.warn(t('match.org_context.resolve_failed'))
        return null
      }

      const ctx: MatchOrgContext = { orgId: myTeam.organizationId, teamId: myTeam.id }
      contextCache.set(teamSlug, ctx)
      return ctx
    } catch {
      notification.warn(t('match.org_context.resolve_failed'))
      return null
    }
  }

  /** 数値 teamId → 解決済みコンテキスト（入口①で home/away participant.teamId と突合した後に使う）。 */
  const contextByTeamIdCache = new Map<number, MatchOrgContextByTeamId>()

  /**
   * 数値 teamId（大会 participant.teamId）から数値 orgId ＋ teamSlug を解決する。
   * 大会対戦表（入口①）では participant.teamId（数値）が起点になるため、slug 起点の
   * resolveContext と対称に、数値 teamId をキーに `/me/teams` を引いて解決する。
   * 当該ユーザーが所属しないチーム（=記録権限を持たない）や親組織未解決の場合は null を返す
   * （症状は隠さず呼び出し側でトースト通知する）。
   */
  async function resolveContextByTeamId(
    teamId: number,
  ): Promise<MatchOrgContextByTeamId | null> {
    const cached = contextByTeamIdCache.get(teamId)
    if (cached !== undefined) return cached

    try {
      const res = await api<{ data: MyTeamItem[] }>('/api/v1/me/teams')
      const myTeam = (res.data ?? []).find((tm) => tm.id === teamId)
      if (!myTeam || typeof myTeam.organizationId !== 'number') {
        notification.warn(t('match.org_context.resolve_failed'))
        return null
      }

      const ctx: MatchOrgContextByTeamId = {
        orgId: myTeam.organizationId,
        teamId: myTeam.id,
        teamSlug: myTeam.slug,
      }
      contextByTeamIdCache.set(teamId, ctx)
      return ctx
    } catch {
      notification.warn(t('match.org_context.resolve_failed'))
      return null
    }
  }

  return {
    resolveContext,
    resolveContextByTeamId,
  }
}
