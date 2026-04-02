import type {
  ElectronicSeal,
  ScopeDefault,
  StampLog,
  VerifyResult,
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

  // === 管理者向け印鑑管理 ===
  async function listAllSeals() {
    const res = await api<{ data: ElectronicSeal[] }>('/api/v1/admin/seals')
    return res.data
  }

  async function regenerateAll() {
    await api('/api/v1/admin/seals/regenerate', { method: 'POST' })
  }

  // === ユーザー印鑑再生成・デフォルト (UI既存利用) ===
  async function regenerateSeals(userId: number) {
    const res = await api<{ data: ElectronicSeal[] }>(`/api/v1/users/${userId}/seals/regenerate`, {
      method: 'POST',
    })
    return res.data
  }

  async function getScopeDefaults(userId: number) {
    const res = await api<{ data: ScopeDefault[] }>(`/api/v1/users/${userId}/seals/scope-defaults`)
    return res.data
  }

  async function updateScopeDefaults(
    userId: number,
    defaults: { scopeType: SealScopeType; scopeId: number | null; variant: SealVariant }[],
  ) {
    const res = await api<{ data: ScopeDefault[] }>(
      `/api/v1/users/${userId}/stamps/scope-defaults`,
      {
        method: 'POST',
        body: { defaults },
      },
    )
    return res.data
  }

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

  async function setScopeDefault(userId: number, body: Record<string, unknown>) {
    await api(`/api/v1/users/${userId}/stamps/scope-defaults`, {
      method: 'POST',
      body,
    })
  }

  return {
    getSeals,
    createSeal,
    getSeal,
    updateSeal,
    deleteSeal,
    listAllSeals,
    regenerateAll,
    regenerateSeals,
    getScopeDefaults,
    updateScopeDefaults,
    getStampLogs,
    stamp,
    revokeStamp,
    verifyStamp,
    setScopeDefault,
  }
}
