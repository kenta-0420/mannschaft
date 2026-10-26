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
 * ## 親組織の数値 orgId について（F01.2.1 §9.2 F1〜F4）
 * 1 チームは複数の組織に ACTIVE 加盟できる。BE `MyTeamResponse.organizations`
 * （`[{id, slug, name}]`・代表親組織が先頭・0 件は `[]`）で全親組織を受け取り、
 * 使う組織は次の優先順で決める。
 *   1. 呼び出し側が渡した `preferredOrgId`（URL クエリ `org`）が親組織に含まれればそれ
 *   2. 呼び出し側が渡した `orgSlug`（大会ページの組織 slug など）が親組織に含まれればそれ
 *   3. 代表親組織（`organizations[0]`。旧 BE 互換で `organizationId` にも縮退）
 * 1・2 で「指定されたのに無効」（数値でない・親組織に含まれない）場合は、代表親組織へ黙って
 * 縮退させない。`orgInvalid: true`・`orgId: null` で返し、選択肢（organizations）は残す。
 * 画面は作成・保存を止め、組織セレクタと警告を出して正しい組織へ戻れるようにする。
 * 選択肢は返り値の `organizations` で画面に渡し、画面側が URL クエリ `org` に載せる。
 *
 * ## キャッシュ方式
 * teamSlug をキーにした Map に解決済みコンテキストを保持する。複数チーム所属ユーザーが
 * チームを切り替えても、別 slug は必ず新たに解決され、最初のチームの値を返し続ける
 * バグ（旧 Phase3-C 検分の指摘）は起きない。解決不能時は null を返し、握り潰さず通知する。
 */

/** 親組織の選択肢（BE MyTeamResponse.organizations の要素）。 */
export interface MatchOrgOption {
  id: number
  slug: string
  name: string
}

/** 数値 orgId ＋ 数値 teamId の解決結果。 */
export interface MatchOrgContext {
  /** 実際に使う組織（選択中、未指定なら代表親組織）。親組織が無いチーム・指定が無効なときは null。 */
  orgId: number | null
  /** 組織が「指定されたのに無効」（不正な値・親組織に無い）。true の間、作成・保存は止める。 */
  orgInvalid: boolean
  teamId: number
  /** チームの全親組織（代表親組織が先頭）。組織選択 UI の選択肢。 */
  organizations: MatchOrgOption[]
}

/**
 * 数値 teamId からの解決結果（入口①の大会対戦表用）。
 * live 画面の遷移先は `/teams/{teamSlug}/matches/...` のため slug も同時に返す。
 */
export interface MatchOrgContextByTeamId extends MatchOrgContext {
  teamSlug: string
}

/** URL クエリ org が指定されたが数値として不正。 */
export const INVALID_ORG = 'invalid' as const

/**
 * 組織の選択指定。`orgId`（URL クエリ org の parseOrgQuery 結果）か `orgSlug`（ページの組織 slug）。
 * 何も指定しない（null/undefined）ときだけ代表親組織を既定にする。
 */
export interface MatchOrgSelector {
  orgId?: number | typeof INVALID_ORG | null
  orgSlug?: string | null
}

interface MyTeamItem {
  id: number
  slug: string
  /** 代表親組織の数値 ID（互換用・非推奨。BE MyTeamResponse.organizationId・null 許容）。 */
  organizationId: number | null
  /** 全親組織（代表親組織が先頭）。旧 BE では未定義。 */
  organizations?: MatchOrgOption[]
}

/**
 * URL クエリ `org`（文字列・配列）を解釈する。
 * 未指定（undefined・null・空配列）だけが null（＝代表親組織が既定）。数値は orgId。
 * 指定されたが数値でない値（`?org=` の空文字を含む）は INVALID_ORG（代表親組織へ落とさず「無効」として扱うため）。
 */
export function parseOrgQuery(raw: unknown): number | typeof INVALID_ORG | null {
  const v = Array.isArray(raw) ? raw[0] : raw
  if (v === undefined || v === null) return null
  if (typeof v !== 'string' || !/^\d+$/.test(v)) return INVALID_ORG
  const n = Number(v)
  return Number.isSafeInteger(n) ? n : INVALID_ORG
}

/** MyTeamItem から全親組織を取り出す（旧 BE は organizationId だけを slug/name なしで縮退）。 */
function organizationsOf(tm: MyTeamItem): MatchOrgOption[] {
  if (Array.isArray(tm.organizations)) return tm.organizations
  return tm.organizationId == null ? [] : [{ id: tm.organizationId, slug: '', name: '' }]
}

/**
 * 使う組織を決める。指定が無ければ代表親組織、指定が無効なら invalid（代表親組織へ落とさない）。
 */
function pickOrg(
  orgs: MatchOrgOption[],
  sel: MatchOrgSelector | undefined,
): { orgId: number | null; invalid: boolean } {
  if (sel?.orgId === INVALID_ORG) return { orgId: null, invalid: true }
  if (sel?.orgId != null) {
    const ok = orgs.some((o) => o.id === sel.orgId)
    return ok ? { orgId: sel.orgId, invalid: false } : { orgId: null, invalid: true }
  }
  if (sel?.orgSlug) {
    const id = orgs.find((o) => o.slug === sel.orgSlug)?.id
    return id === undefined ? { orgId: null, invalid: true } : { orgId: id, invalid: false }
  }
  return { orgId: orgs[0]?.id ?? null, invalid: false }
}

export function useMatchOrgContext() {
  const api = useApi()
  const notification = useNotification()
  const { t } = useI18n()

  /** `/me/teams` の取得結果（同一画面内での API 重複呼び出しを防ぐ）。 */
  let myTeamsPromise: Promise<MyTeamItem[]> | null = null

  async function fetchMyTeams(): Promise<MyTeamItem[]> {
    if (myTeamsPromise === null) {
      myTeamsPromise = api<{ data: MyTeamItem[] }>('/api/v1/me/teams')
        .then((res) => res.data ?? [])
        .catch((e: unknown) => {
          myTeamsPromise = null
          throw e
        })
    }
    return myTeamsPromise
  }

  /**
   * teamSlug（URL slug 文字列）から数値 orgId ＋ 数値 teamId ＋ 親組織の選択肢を解決する。
   * `selector.orgId`（URL クエリ org）が指定され、チームの親組織に含まれればその組織を使う。
   * 含まれない場合は `orgInvalid: true`（orgId は null・選択肢は残す）。チームが無い／取得失敗は null。
   * 別の teamSlug・別の selector は必ず新たに判定する（キャッシュするのは /me/teams の応答のみ）。
   */
  async function resolveContext(
    teamSlug: string,
    selector?: MatchOrgSelector,
  ): Promise<MatchOrgContext | null> {
    try {
      const teams = await fetchMyTeams()
      // Bug A 修正: BE の CalendarScopeDto.scopeId は Long（数値）を返すため、
      // カレンダーから開いた場合は teamSlug が "12345" のような数値文字列になりうる。
      // slug 一致だけでは永遠に null が返るため、数値 ID での一致も許容する。
      const myTeam = teams.find((tm) => tm.slug === teamSlug || String(tm.id) === String(teamSlug))
      // 当該チームが /me/teams に無いのは想定内（単独チーム等）。警告トーストは出さず null。
      // 真のエラー（/me/teams 取得失敗）は下の catch で警告する。
      if (!myTeam) return null

      const organizations = organizationsOf(myTeam)
      const { orgId, invalid } = pickOrg(organizations, selector)
      return { orgId, orgInvalid: invalid, teamId: myTeam.id, organizations }
    } catch {
      notification.warn(t('match.org_context.resolve_failed'))
      return null
    }
  }

  /**
   * 数値 teamId（大会 participant.teamId）から数値 orgId ＋ teamSlug を解決する。
   * 大会対戦表（入口①）では participant.teamId（数値）が起点になるため、slug 起点の
   * resolveContext と対称に `/me/teams` を引く。大会の組織は `selector.orgSlug`（ページの
   * `[slug]`）で指定し、チームの親組織から推測しない（F01.2.1 §9.2 F2）。
   * 当該ユーザーが所属しないチーム・取得失敗は null。指定の組織に加盟していないチームは `orgInvalid: true`。
   */
  async function resolveContextByTeamId(
    teamId: number,
    selector?: MatchOrgSelector,
  ): Promise<MatchOrgContextByTeamId | null> {
    try {
      const teams = await fetchMyTeams()
      const myTeam = teams.find((tm) => tm.id === teamId)
      if (!myTeam) return null

      const organizations = organizationsOf(myTeam)
      const { orgId, invalid } = pickOrg(organizations, selector)
      return {
        orgId,
        orgInvalid: invalid,
        teamId: myTeam.id,
        teamSlug: myTeam.slug,
        organizations,
      }
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
