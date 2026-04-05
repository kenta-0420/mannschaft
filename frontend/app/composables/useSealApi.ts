import type {
  ElectronicSeal,
  ScopeDefault,
  SealPreview,
  StampLog,
  VerifyResult,
  RegenerateAllStatus,
  SealVariant,
  SealScopeType,
} from '~/types/seal'

export function useSealApi() {
  const api = useApi()

  // === ユーザー印鑑一覧 ===
  async function getSeals(userId: number) {
    const res = await api<{ data: ElectronicSeal[] }>(`/api/v1/users/${userId}/seals`)
    return res.data
  }

  // === 個別印鑑 CRUD ===
  async function createSeal(userId: number, body: Record<string, unknown>) {
    const res = await api<{ data: ElectronicSeal }>(`/api/v1/users/${userId}/seals`, {
      method: 'POST',
      body,
    })
    return res.data
  }

  async function getSeal(userId: number, sealId: number) {
    const res = await api<{ data: ElectronicSeal }>(`/api/v1/users/${userId}/seals/${sealId}`)
    return res.data
  }

  async function updateSeal(userId: number, sealId: number, body: Record<string, unknown>) {
    const res = await api<{ data: ElectronicSeal }>(`/api/v1/users/${userId}/seals/${sealId}`, {
      method: 'PUT',
      body,
    })
    return res.data
  }

  async function deleteSeal(userId: number, sealId: number) {
    await api(`/api/v1/users/${userId}/seals/${sealId}`, { method: 'DELETE' })
  }

  // === 印鑑プレビュー・再生成 ===
  async function previewSeal(
    userId: number,
    params?: { overrideLastName?: string; overrideFirstName?: string },
  ) {
    const query = new URLSearchParams()
    if (params?.overrideLastName) query.set('override_last_name', params.overrideLastName)
    if (params?.overrideFirstName) query.set('override_first_name', params.overrideFirstName)
    const qs = query.toString()
    const res = await api<{ data: { previews: SealPreview[] } }>(
      `/api/v1/users/${userId}/seals/preview${qs ? `?${qs}` : ''}`,
    )
    return res.data.previews
  }

  async function regenerateSeals(userId: number) {
    const res = await api<{ data: ElectronicSeal[] }>(`/api/v1/users/${userId}/seals/regenerate`, {
      method: 'POST',
    })
    return res.data
  }

  // === スコープ別デフォルト ===
  async function getScopeDefaults(userId: number) {
    const res = await api<{ data: ScopeDefault[] }>(`/api/v1/users/${userId}/seals/scope-defaults`)
    return res.data
  }

  async function updateScopeDefaults(
    userId: number,
    defaults: { scopeType: SealScopeType; scopeId: number | null; variant: SealVariant }[],
  ) {
    const res = await api<{ data: ScopeDefault[] }>(
      `/api/v1/users/${userId}/seals/scope-defaults`,
      {
        method: 'PUT',
        body: { defaults },
      },
    )
    return res.data
  }

  // === 押印ログ ===
  async function getStampLogs(userId: number, params?: { cursor?: string; size?: number }) {
    const query = new URLSearchParams()
    if (params?.cursor) query.set('cursor', params.cursor)
    if (params?.size) query.set('size', String(params.size))
    const qs = query.toString()
    const res = await api<{ data: StampLog[]; meta: { nextCursor: string | null } }>(
      `/api/v1/users/${userId}/stamps${qs ? `?${qs}` : ''}`,
    )
    return res
  }

  // === 押印 ===
  async function stamp(userId: number, body: Record<string, unknown>) {
    await api(`/api/v1/users/${userId}/stamps`, { method: 'POST', body })
  }

  async function revokeStamp(userId: number, stampLogId: number, reason: string) {
    await api(`/api/v1/users/${userId}/stamps/${stampLogId}/revoke`, {
      method: 'POST',
      body: { reason },
    })
  }

  async function verifyStamp(userId: number, stampLogId: number) {
    const res = await api<{ data: VerifyResult }>(
      `/api/v1/users/${userId}/stamps/${stampLogId}/verify`,
    )
    return res.data
  }

  // === 管理者向け印鑑管理 ===
  async function listAllSeals() {
    const res = await api<{ data: ElectronicSeal[] }>('/api/v1/admin/seals')
    return res.data
  }

  async function regenerateAll() {
    await api('/api/v1/admin/seals/regenerate-all', { method: 'POST' })
  }

  async function regenerateAllStatus(jobId: string) {
    const res = await api<{ data: RegenerateAllStatus }>(
      `/api/v1/admin/seals/regenerate-all/${jobId}/status`,
    )
    return res.data
  }

  return {
    getSeals,
    createSeal,
    getSeal,
    updateSeal,
    deleteSeal,
    previewSeal,
    regenerateSeals,
    getScopeDefaults,
    updateScopeDefaults,
    getStampLogs,
    stamp,
    revokeStamp,
    verifyStamp,
    listAllSeals,
    regenerateAll,
    regenerateAllStatus,
  }
}
