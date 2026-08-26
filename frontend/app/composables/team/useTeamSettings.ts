/**
 * チームのアクセス要件・ブロック・コンテンツ有料化設定を扱うサブ composable。
 *
 * useTeamApi を分割した責務マップのうち「設定系（アクセス制御・収益化）」を担当する。
 * 公開関数のシグネチャは元の useTeamApi と同一を維持している。
 */
export function useTeamSettings() {
  const api = useApi()

  // === アクセス要件 ===
  async function getAccessRequirements(teamSlug: string) {
    return api<{ data: Record<string, unknown> }>(`/api/v1/teams/${teamSlug}/access-requirements`)
  }

  async function updateAccessRequirements(teamSlug: string, body: Record<string, unknown>) {
    return api<{ data: Record<string, unknown> }>(`/api/v1/teams/${teamSlug}/access-requirements`, {
      method: 'PUT',
      body,
    })
  }

  // === ブロック管理 ===
  async function getBlocks(teamSlug: string) {
    return api<{
      data: Array<{
        id: number
        blockedUserId: number
        blockedDisplayName: string
        reason: string | null
        createdAt: string
      }>
    }>(`/api/v1/teams/${teamSlug}/blocks`)
  }

  async function createBlock(teamSlug: string, body: { userId: number; reason?: string }) {
    return api(`/api/v1/teams/${teamSlug}/blocks`, { method: 'POST', body })
  }

  async function removeBlock(teamSlug: string, userId: number) {
    return api(`/api/v1/teams/${teamSlug}/blocks/${userId}`, { method: 'DELETE' })
  }

  // === コンテンツ有料化設定 ===
  async function getContentPaymentGates(teamSlug: string) {
    return api<{
      data: Record<string, unknown>[]
      meta: { page: number; size: number; totalElements: number; totalPages: number }
    }>(`/api/v1/teams/${teamSlug}/content-payment-gates`)
  }

  async function updateContentPaymentGates(teamSlug: string, body: Record<string, unknown>) {
    return api(`/api/v1/teams/${teamSlug}/content-payment-gates`, { method: 'PUT', body })
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
