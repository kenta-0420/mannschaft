import type { FacilitySettingsResponse, FacilityStatsResponse } from '~/types/facility'

/**
 * 施設機能のスコープ単位設定および統計の API ラッパー。
 *
 * リファクタリング第 12 弾で useFacilityApi から分離した。
 * 公開関数の名前・シグネチャは分割前と完全に同一を保つ。
 */
export function useFacilitySettings() {
  const api = useApi()

  function buildBase(scopeType: 'team' | 'organization', scopeId: string) {
    return scopeType === 'team' ? `/api/v1/teams/${scopeId}` : `/api/v1/organizations/${scopeId}`
  }

  // === Settings ===
  async function getFacilitySettings(scopeType: 'team' | 'organization', scopeId: string) {
    return api<{ data: FacilitySettingsResponse }>(
      `${buildBase(scopeType, scopeId)}/facilities/settings`,
    )
  }

  async function updateFacilitySettings(
    scopeType: 'team' | 'organization',
    scopeId: string,
    body: Record<string, unknown>,
  ) {
    return api<{ data: FacilitySettingsResponse }>(
      `${buildBase(scopeType, scopeId)}/facilities/settings`,
      { method: 'PUT', body },
    )
  }

  // === Stats ===
  async function getFacilityStats(scopeType: 'team' | 'organization', scopeId: string) {
    return api<{ data: FacilityStatsResponse }>(`${buildBase(scopeType, scopeId)}/facilities/stats`)
  }

  return {
    // Settings & Stats
    getFacilitySettings,
    updateFacilitySettings,
    getFacilityStats,
  }
}
