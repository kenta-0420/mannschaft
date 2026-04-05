export interface PublicStats {
  totalUsers: number
  totalTeams: number
  totalOrganizations: number
  countryBreakdown: Record<string, { users: number; teams: number; organizations: number }> | null
}
