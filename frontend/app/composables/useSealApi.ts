import type { ElectronicSeal, ScopeDefault, StampLog, VerifyResult, SealVariant, SealScopeType } from '~/types/seal'

export function useSealApi() {
  const api = useApi()

  async function getSeals(userId: number) {
    const res = await api<{ data: ElectronicSeal[] }>(`/api/v1/users/${userId}/seals`)
    return res.data
  }

  async function previewSeals(userId: number) {
    const res = await api<{ data: ElectronicSeal[] }>(`/api/v1/users/${userId}/seals/preview`)
    return res.data
  }

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

  async function updateScopeDefaults(userId: number, defaults: { scopeType: SealScopeType; scopeId: number | null; variant: SealVariant }[]) {
    const res = await api<{ data: ScopeDefault[] }>(`/api/v1/users/${userId}/seals/scope-defaults`, {
      method: 'PUT',
      body: { defaults },
    })
    return res.data
  }

  async function getStampLogs(userId: number, params?: { cursor?: string; size?: number }) {
    const query = new URLSearchParams()
    if (params?.cursor) query.set('cursor', params.cursor)
    if (params?.size) query.set('size', String(params.size))
    const qs = query.toString()
    const res = await api<{ data: StampLog[]; meta: { nextCursor: string | null } }>(
      `/api/v1/users/${userId}/seals/stamps${qs ? `?${qs}` : ''}`,
    )
    return res
  }

  async function revokeStamp(stampId: number, reason: string) {
    await api(`/api/v1/seal/stamps/${stampId}/revoke`, {
      method: 'POST',
      body: { reason },
    })
  }

  async function verifyStamp(stampId: number) {
    const res = await api<{ data: VerifyResult }>(`/api/v1/seal/stamps/${stampId}/verify`)
    return res.data
  }

  return { getSeals, previewSeals, regenerateSeals, getScopeDefaults, updateScopeDefaults, getStampLogs, revokeStamp, verifyStamp }
}
