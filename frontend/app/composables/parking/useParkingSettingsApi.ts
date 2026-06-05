import type { ParkingSettingsResponse, ParkingStatsResponse } from '~/types/parking'
import { buildBase } from './useParkingApiBase'

export function useParkingSettingsApi() {
  const api = useApi()

  // === Settings ===
  async function getParkingSettings(scopeType: 'team' | 'organization', scopeId: string) {
    return api<{ data: ParkingSettingsResponse }>(
      `${buildBase(scopeType, scopeId)}/parking/settings`,
    )
  }

  async function updateParkingSettings(
    scopeType: 'team' | 'organization',
    scopeId: string,
    body: Record<string, unknown>,
  ) {
    return api<{ data: ParkingSettingsResponse }>(
      `${buildBase(scopeType, scopeId)}/parking/settings`,
      { method: 'PUT', body },
    )
  }

  // === Stats ===
  async function getParkingStats(scopeType: 'team' | 'organization', scopeId: string) {
    return api<{ data: ParkingStatsResponse }>(`${buildBase(scopeType, scopeId)}/parking/stats`)
  }

  return {
    getParkingSettings,
    updateParkingSettings,
    getParkingStats,
  }
}
