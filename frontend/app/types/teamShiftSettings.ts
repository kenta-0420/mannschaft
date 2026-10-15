export interface TeamShiftSettings {
  teamId: number
  reminder48hEnabled: boolean
  reminder24hEnabled: boolean
  reminder12hEnabled: boolean
}

export interface UpdateTeamShiftSettingsRequest {
  reminder48hEnabled: boolean
  reminder24hEnabled: boolean
  reminder12hEnabled: boolean
}
