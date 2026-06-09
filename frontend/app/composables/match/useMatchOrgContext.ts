/**
 * F08.10 組織 ID 解決の共通 composable（3-A の 🟡 回収・04_frontend_and_ux.md §G.4）。
 *
 * index.vue / new.vue / live.vue が個別に持っていた「teamApi.getOrganizations(teamId) →
 * 先頭組織の id を採る」ロジックを 1 箇所に集約する。teamId（文字列）を渡すと当該チームの
 * 所属組織 ID（数値）を返す。解決済みなら再取得しない（teamId をキーにした Map キャッシュ）。
 *
 * F08.10 の単独試合 API は `/organizations/{orgId}/teams/{teamId}/...` 配下のため、
 * org コンテキストの解決はページ側の前提手続きであり、各ページで重複していた
 * （repair-plan.vue / usePagesteams の既存作法）。
 *
 * ## キャッシュ方式の根拠
 * 旧実装は単一 orgId ref で「既に解決済みなら即返す」早期 return を持っていた。
 * 複数チーム所属ユーザーがチームを切り替えると最初の teamId の orgId を返し続けるバグ
 * （F08.10 Phase3-C 検分 🟠1件）があったため、teamId をキーにした Map に変更した。
 * 同一 teamId への重複 API 呼び出しは防止しつつ、別 teamId は常に正しい orgId を解決する。
 */
export function useMatchOrgContext() {
  const teamApi = useTeamApi()

  /** teamId → orgId のキャッシュ（同一 teamId の API 重複呼び出しを防ぐ） */
  const orgIdCache = new Map<string, number>()

  /**
   * teamId（文字列）から所属組織の先頭 ID を解決する。
   * 同一 teamId が既にキャッシュ済みなら API を再度叩かずキャッシュ値を返す。
   * 別の teamId が渡された場合は必ず新たに API を呼び出し正しい orgId を返す。
   * 解決できなかった場合は null を返す（呼び出し側で null ガードする）。
   */
  async function resolveOrgId(teamIdStr: string): Promise<number | null> {
    const cached = orgIdCache.get(teamIdStr)
    if (cached !== undefined) return cached

    const res = await teamApi.getOrganizations(teamIdStr)
    const first = (res.data ?? [])[0]
    const rawId = first?.id
    if (typeof rawId === 'number') {
      orgIdCache.set(teamIdStr, rawId)
      return rawId
    }
    return null
  }

  return {
    resolveOrgId,
  }
}
