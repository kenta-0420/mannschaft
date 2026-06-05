/**
 * チームのアクセス要件・ブロック・コンテンツ有料化設定を扱うサブ composable。
 *
 * useTeamApi を分割した責務マップのうち「設定系（アクセス制御・収益化）」を担当する。
 * 公開関数のシグネチャは元の useTeamApi と同一を維持している。
 */
export function useTeamSettings() {
  const api = useApi()

  // === アクセス要件 ===
  async function getAccessRequirements(teamId: string) {
    return api<{ data: Record<string, unknown> }>(`/api/v1/teams/${teamId}/access-requirements`)
  }

  async function updateAccessRequirements(teamId: string, body: Record<string, unknown>) {
    return api<{ data: Record<string, unknown> }>(`/api/v1/teams/${teamId}/access-requirements`, {
      method: 'PUT',
      body,
    })
  }

  // === ブロック管理 ===
  async function getBlocks(teamId: string) {
    return api<{
      data: Array<{
        id: number
        blockedUserId: number
        blockedDisplayName: string
        reason: string | null
        createdAt: string
      }>
    }>(`/api/v1/teams/${teamId}/blocks`)
  }

  async function createBlock(teamId: string, body: { userId: number; reason?: string }) {
    return api(`/api/v1/teams/${teamId}/blocks`, { method: 'POST', body })
  }

  async function removeBlock(teamId: string, userId: number) {
    return api(`/api/v1/teams/${teamId}/blocks/${userId}`, { method: 'DELETE' })
  }

  // === コンテンツ有料化設定 ===
  async function getContentPaymentGates(teamId: string) {
    return api<{
      data: Record<string, unknown>[]
      meta: { page: number; size: number; totalElements: number; totalPages: number }
    }>(`/api/v1/teams/${teamId}/content-payment-gates`)
  }

  async function updateContentPaymentGates(teamId: string, body: Record<string, unknown>) {
    return api(`/api/v1/teams/${teamId}/content-payment-gates`, { method: 'PUT', body })
  }

  return {
    getAccessRequirements,
    updateAccessRequirements,
    getBlocks,
    createBlock,
    removeBlock,
    getContentPaymentGates,
    updateContentPaymentGates,
  }
}
