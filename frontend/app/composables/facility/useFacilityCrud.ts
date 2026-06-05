import type {
  FacilityResponse,
  FacilityDetailResponse,
  FacilityEquipmentResponse,
  TimeRateResponse,
  UsageRuleResponse,
} from '~/types/facility'

/**
 * 施設本体の CRUD と関連リソース（空き状況・利用料・利用規約・備品）の API ラッパー。
 *
 * リファクタリング第 12 弾で useFacilityApi から分離した。
 * 公開関数の名前・シグネチャは分割前と完全に同一を保つ。
 */
export function useFacilityCrud() {
  const api = useApi()

  function buildBase(scopeType: 'team' | 'organization', scopeId: string) {
    return scopeType === 'team' ? `/api/v1/teams/${scopeId}` : `/api/v1/organizations/${scopeId}`
  }

  // === Facilities CRUD ===
  async function getFacilities(
    scopeType: 'team' | 'organization',
    scopeId: string,
    params?: Record<string, unknown>,
  ) {
    const q = new URLSearchParams()
    if (params)
      for (const [k, v] of Object.entries(params)) {
        if (v != null) q.set(k, String(v))
      }
    const qs = q.toString()
    return api<{ data: FacilityResponse[] }>(
      `${buildBase(scopeType, scopeId)}/facilities${qs ? `?${qs}` : ''}`,
    )
  }

  async function createFacility(
    scopeType: 'team' | 'organization',
    scopeId: string,
    body: Record<string, unknown>,
  ) {
    return api<{ data: FacilityResponse }>(`${buildBase(scopeType, scopeId)}/facilities`, {
      method: 'POST',
      body,
    })
  }

  async function bulkCreateFacilities(
    scopeType: 'team' | 'organization',
    scopeId: string,
    body: Record<string, unknown>,
  ) {
    return api(`${buildBase(scopeType, scopeId)}/facilities/bulk-create`, { method: 'POST', body })
  }

  async function getFacility(
    scopeType: 'team' | 'organization',
    scopeId: string,
    facilityId: number,
  ) {
    return api<{ data: FacilityDetailResponse }>(
      `${buildBase(scopeType, scopeId)}/facilities/${facilityId}`,
    )
  }

  async function updateFacility(
    scopeType: 'team' | 'organization',
    scopeId: string,
    facilityId: number,
    body: Record<string, unknown>,
  ) {
    return api<{ data: FacilityResponse }>(
      `${buildBase(scopeType, scopeId)}/facilities/${facilityId}`,
      { method: 'PUT', body },
    )
  }

  async function deleteFacility(
    scopeType: 'team' | 'organization',
    scopeId: string,
    facilityId: number,
  ) {
    return api(`${buildBase(scopeType, scopeId)}/facilities/${facilityId}`, { method: 'DELETE' })
  }

  // === Facility Availability ===
  async function getFacilityAvailability(
    scopeType: 'team' | 'organization',
    scopeId: string,
    facilityId: number,
    params?: Record<string, unknown>,
  ) {
    const q = new URLSearchParams()
    if (params)
      for (const [k, v] of Object.entries(params)) {
        if (v != null) q.set(k, String(v))
      }
    const qs = q.toString()
    return api(
      `${buildBase(scopeType, scopeId)}/facilities/${facilityId}/availability${qs ? `?${qs}` : ''}`,
    )
  }

  // === Facility Rates ===
  async function getFacilityRates(
    scopeType: 'team' | 'organization',
    scopeId: string,
    facilityId: number,
  ) {
    return api<{ data: TimeRateResponse[] }>(
      `${buildBase(scopeType, scopeId)}/facilities/${facilityId}/rates`,
    )
  }

  async function updateFacilityRates(
    scopeType: 'team' | 'organization',
    scopeId: string,
    facilityId: number,
    body: Record<string, unknown>,
  ) {
    return api(`${buildBase(scopeType, scopeId)}/facilities/${facilityId}/rates`, {
      method: 'PUT',
      body,
    })
  }

  // === Facility Rules ===
  async function getFacilityRules(
    scopeType: 'team' | 'organization',
    scopeId: string,
    facilityId: number,
  ) {
    return api<{ data: UsageRuleResponse }>(
      `${buildBase(scopeType, scopeId)}/facilities/${facilityId}/rules`,
    )
  }

  async function updateFacilityRules(
    scopeType: 'team' | 'organization',
    scopeId: string,
    facilityId: number,
    body: Record<string, unknown>,
  ) {
    return api<{ data: UsageRuleResponse }>(
      `${buildBase(scopeType, scopeId)}/facilities/${facilityId}/rules`,
      { method: 'PUT', body },
    )
  }

  // === Facility Equipment ===
  async function getEquipment(
    scopeType: 'team' | 'organization',
    scopeId: string,
    facilityId: number,
  ) {
    return api<{ data: FacilityEquipmentResponse[] }>(
      `${buildBase(scopeType, scopeId)}/facilities/${facilityId}/equipment`,
    )
  }

  async function createEquipment(
    scopeType: 'team' | 'organization',
    scopeId: string,
    facilityId: number,
    body: Record<string, unknown>,
  ) {
    return api<{ data: FacilityEquipmentResponse }>(
      `${buildBase(scopeType, scopeId)}/facilities/${facilityId}/equipment`,
      { method: 'POST', body },
    )
  }

  async function updateEquipment(
    scopeType: 'team' | 'organization',
    scopeId: string,
    facilityId: number,
    equipmentId: number,
    body: Record<string, unknown>,
  ) {
    return api<{ data: FacilityEquipmentResponse }>(
      `${buildBase(scopeType, scopeId)}/facilities/${facilityId}/equipment/${equipmentId}`,
      { method: 'PUT', body },
    )
  }

  async function deleteEquipment(
    scopeType: 'team' | 'organization',
    scopeId: string,
    facilityId: number,
    equipmentId: number,
  ) {
    return api(
      `${buildBase(scopeType, scopeId)}/facilities/${facilityId}/equipment/${equipmentId}`,
      { method: 'DELETE' },
    )
  }

  return {
    // Facilities CRUD
    getFacilities,
    createFacility,
    bulkCreateFacilities,
    getFacility,
    updateFacility,
    deleteFacility,
    // Availability
    getFacilityAvailability,
    // Rates
    getFacilityRates,
    updateFacilityRates,
    // Rules
    getFacilityRules,
    updateFacilityRules,
    // Equipment
    getEquipment,
    createEquipment,
    updateEquipment,
    deleteEquipment,
  }
}
