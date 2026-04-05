import type { EquipmentResponse, EquipmentHistory } from '~/types/equipment'

export function useEquipmentApi() {
  const api = useApi()

  function buildBase(scopeType: 'team' | 'organization', scopeId: number) {
    return scopeType === 'team' ? `/api/v1/teams/${scopeId}` : `/api/v1/organizations/${scopeId}`
  }

  async function getEquipmentList(
    scopeType: 'team' | 'organization',
    scopeId: number,
    params?: Record<string, unknown>,
  ) {
    const query = new URLSearchParams()
    if (params)
      for (const [k, v] of Object.entries(params)) {
        if (v !== undefined && v !== null) query.set(k, String(v))
      }
    return api<{
      data: EquipmentResponse[]
      meta: { page: number; size: number; totalElements: number; totalPages: number }
    }>(`${buildBase(scopeType, scopeId)}/equipment?${query}`)
  }

  async function getEquipment(
    scopeType: 'team' | 'organization',
    scopeId: number,
    equipId: number,
  ) {
    return api<{ data: EquipmentResponse }>(`${buildBase(scopeType, scopeId)}/equipment/${equipId}`)
  }

  async function createEquipment(
    scopeType: 'team' | 'organization',
    scopeId: number,
    body: Record<string, unknown>,
  ) {
    return api<{ data: EquipmentResponse }>(`${buildBase(scopeType, scopeId)}/equipment`, {
      method: 'POST',
      body,
    })
  }

  async function updateEquipment(
    scopeType: 'team' | 'organization',
    scopeId: number,
    equipId: number,
    body: Record<string, unknown>,
  ) {
    return api<{ data: EquipmentResponse }>(
      `${buildBase(scopeType, scopeId)}/equipment/${equipId}`,
      { method: 'PUT', body },
    )
  }

  async function deleteEquipment(
    scopeType: 'team' | 'organization',
    scopeId: number,
    equipId: number,
  ) {
    return api(`${buildBase(scopeType, scopeId)}/equipment/${equipId}`, { method: 'DELETE' })
  }

  async function assignEquipment(
    scopeType: 'team' | 'organization',
    scopeId: number,
    equipId: number,
    userId: number,
    returnDueDate?: string,
  ) {
    return api(`${buildBase(scopeType, scopeId)}/equipment/${equipId}/assign`, {
      method: 'POST',
      body: { userId, returnDueDate },
    })
  }

  async function bulkAssignEquipment(
    scopeType: 'team' | 'organization',
    scopeId: number,
    equipId: number,
    body: Record<string, unknown>,
  ) {
    return api(`${buildBase(scopeType, scopeId)}/equipment/${equipId}/assign-bulk`, {
      method: 'POST',
      body,
    })
  }

  async function returnEquipment(
    scopeType: 'team' | 'organization',
    scopeId: number,
    equipId: number,
  ) {
    return api(`${buildBase(scopeType, scopeId)}/equipment/${equipId}/return`, { method: 'PATCH' })
  }

  async function bulkReturnEquipment(
    scopeType: 'team' | 'organization',
    scopeId: number,
    equipId: number,
    body: Record<string, unknown>,
  ) {
    return api(`${buildBase(scopeType, scopeId)}/equipment/${equipId}/return-bulk`, {
      method: 'PATCH',
      body,
    })
  }

  async function consumeEquipment(
    scopeType: 'team' | 'organization',
    scopeId: number,
    equipId: number,
    body: Record<string, unknown>,
  ) {
    return api(`${buildBase(scopeType, scopeId)}/equipment/${equipId}/consume`, {
      method: 'POST',
      body,
    })
  }

  async function getHistory(scopeType: 'team' | 'organization', scopeId: number, equipId: number) {
    return api<{ data: EquipmentHistory[] }>(
      `${buildBase(scopeType, scopeId)}/equipment/${equipId}/history`,
    )
  }

  async function getOverdue(scopeType: 'team' | 'organization', scopeId: number) {
    return api<{ data: EquipmentResponse[] }>(`${buildBase(scopeType, scopeId)}/equipment/overdue`)
  }

  async function getCategories(scopeType: 'team' | 'organization', scopeId: number) {
    return api(`${buildBase(scopeType, scopeId)}/equipment/categories`)
  }

  async function getQrCodes(scopeType: 'team' | 'organization', scopeId: number) {
    return api(`${buildBase(scopeType, scopeId)}/equipment/qr-codes`)
  }

  async function getImagePresignedUrl(
    scopeType: 'team' | 'organization',
    scopeId: number,
    equipId: number,
    body: Record<string, unknown>,
  ) {
    return api(`${buildBase(scopeType, scopeId)}/equipment/${equipId}/image/presigned-url`, {
      method: 'POST',
      body,
    })
  }

  async function deleteImage(scopeType: 'team' | 'organization', scopeId: number, equipId: number) {
    return api(`${buildBase(scopeType, scopeId)}/equipment/${equipId}/image`, { method: 'DELETE' })
  }

  async function getMyAssignments() {
    return api<{ data: EquipmentResponse[] }>('/api/v1/equipment/my-assignments')
  }

  return {
    getEquipmentList,
    getEquipment,
    createEquipment,
    updateEquipment,
    deleteEquipment,
    assignEquipment,
    bulkAssignEquipment,
    returnEquipment,
    bulkReturnEquipment,
    consumeEquipment,
    getHistory,
    getOverdue,
    getCategories,
    getQrCodes,
    getImagePresignedUrl,
    deleteImage,
    getMyAssignments,
  }
}
