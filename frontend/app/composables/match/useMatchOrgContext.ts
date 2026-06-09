/**
 * F08.10 組織 ID 解決の共通 composable（3-A の 🟡 回収・04_frontend_and_ux.md §G.4）。
 *
 * index.vue / new.vue / live.vue が個別に持っていた「teamApi.getOrganizations(teamId) →
 * 先頭組織の id を採る」ロジックを 1 箇所に集約する。teamId（文字列）を渡すと当該チームの
 * 所属組織 ID（数値）を返す。解決済みなら再取得しない（同一 composable インスタンス内キャッシュ）。
 *
 * F08.10 の単独試合 API は `/organizations/{orgId}/teams/{teamId}/...` 配下のため、
 * org コンテキストの解決はページ側の前提手続きであり、各ページで重複していた
 * （repair-plan.vue / usePagesteams の既存作法）。
 */
export function useMatchOrgContext() {
  const teamApi = useTeamApi()

  /** 解決済み組織 ID（null=未解決） */
  const orgId = ref<number | null>(null)

  /**
   * teamId（文字列）から所属組織の先頭 ID を解決する。
   * 既に解決済みなら API を再度叩かずキャッシュ値を返す。
   * 解決できなかった場合は null のまま返す（呼び出し側で null ガードする）。
   */
  async function resolveOrgId(teamIdStr: string): Promise<number | null> {
    if (orgId.value !== null) return orgId.value
    const res = await teamApi.getOrganizations(teamIdStr)
    const first = (res.data ?? [])[0]
    const rawId = first?.id
    if (typeof rawId === 'number') {
      orgId.value = rawId
    }
    return orgId.value
  }

  return {
    orgId,
    resolveOrgId,
  }
}
