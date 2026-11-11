/**
 * チャット作成 API（POST /api/v1/chat/channels）が要求する数値のスコープ ID を解決する（CMP-261105-1507）。
 *
 * 画面は URL 識別子（slug）しか持たないが、BE の CreateChannelRequest は
 * teamId / organizationId を Long で受ける。slug のまま送ると型変換に失敗して 400 になる。
 * 数値 ID は TeamResponse / OrganizationResponse の numericId から得る。
 * 取得できなければ握りつぶさず例外にする（呼び出し側が作成失敗として通知する）。
 */
export function useChatScopeIds() {
  const teamApi = useTeamApi()
  const orgApi = useOrganizationApi()

  async function resolveScopeIds(scope: {
    teamId?: string
    organizationId?: string
  }): Promise<{ teamId?: number; organizationId?: number }> {
    if (scope.teamId) {
      const numericId = (await teamApi.getTeam(scope.teamId)).data.numericId
      if (!numericId) throw new Error(`team numericId missing: ${scope.teamId}`)
      return { teamId: numericId }
    }
    if (scope.organizationId) {
      const numericId = (await orgApi.getOrganization(scope.organizationId)).data.numericId
      if (!numericId) throw new Error(`organization numericId missing: ${scope.organizationId}`)
      return { organizationId: numericId }
    }
    return {}
  }

  return { resolveScopeIds }
}
