import type {
  TeamShiftSettings,
  UpdateTeamShiftSettingsRequest,
} from '~/types/teamShiftSettings'

/**
 * チームシフト設定（リマインド間隔カスタマイズ）API。
 * BE: TeamShiftSettingsController（エンベロープ無しで DTO を直接返す）。
 */
export function useTeamShiftSettingsApi() {
  const api = useApi()
  const BASE = '/api/v1/teams'

  async function getShiftSettings(teamSlug: string): Promise<TeamShiftSettings> {
    return api<TeamShiftSettings>(`${BASE}/${teamSlug}/shift-settings`)
  }

  async function updateShiftSettings(
    teamSlug: string,
    req: UpdateTeamShiftSettingsRequest,
  ): Promise<TeamShiftSettings> {
    return api<TeamShiftSettings>(`${BASE}/${teamSlug}/shift-settings`, {
      method: 'PATCH',
      body: req,
    })
  }

  return {
    getShiftSettings,
    updateShiftSettings,
  }
}
