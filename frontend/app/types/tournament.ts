export type TournamentFormat = 'LEAGUE' | 'KNOCKOUT' | 'GROUP_KNOCKOUT'
export type TournamentStatus = 'DRAFT' | 'OPEN' | 'IN_PROGRESS' | 'COMPLETED' | 'ARCHIVED'

export interface TournamentResponse {
  id: number
  organizationId: number
  title: string
  description: string | null
  sportCategory: string
  format: TournamentFormat
  status: TournamentStatus
  isPublic: boolean
  winPoints: number
  drawPoints: number
  lossPoints: number
  seasonYear: number
  startDate: string | null
  endDate: string | null
  divisions: TournamentDivision[]
  createdAt: string
  updatedAt: string
}

export interface TournamentDivision {
  id: number
  tournamentId: number
  name: string
  level: number
  maxParticipants: number
  participantCount: number
  promotionSlots: number
  relegationSlots: number
}

export interface TournamentParticipant {
  id: number
  divisionId: number
  teamId: number
  teamName: string
  teamLogoUrl: string | null
  registeredAt: string
}

export interface TournamentMatchday {
  id: number
  divisionId: number
  roundNumber: number
  matchDate: string | null
  matches: TournamentMatch[]
}

export interface TournamentMatch {
  id: number
  matchdayId: number
  homeTeamId: number
  homeTeamName: string
  awayTeamId: number
  awayTeamName: string
  homeScore: number | null
  awayScore: number | null
  status: 'SCHEDULED' | 'IN_PROGRESS' | 'COMPLETED' | 'CANCELLED' | 'POSTPONED'
  sets: Array<{ setNumber: number; homeScore: number; awayScore: number }>
  playedAt: string | null
}

export interface TournamentStanding {
  rank: number
  teamId: number
  teamName: string
  teamLogoUrl: string | null
  played: number
  won: number
  drawn: number
  lost: number
  goalsFor: number
  goalsAgainst: number
  goalDifference: number
  points: number
}

export interface IndividualRanking {
  rank: number
  userId: number
  displayName: string
  teamName: string
  statKey: string
  statLabel: string
  value: number
}

export interface TournamentTemplate {
  id: number
  organizationId: number
  name: string
  sportCategory: string
  format: TournamentFormat
  winPoints: number
  drawPoints: number
  lossPoints: number
  tiebreakers: string[]
  statDefs: Array<{ key: string; label: string; aggregationType: string }>
}

export interface TournamentPreset {
  id: number
  name: string
  sportCategory: string
  format: TournamentFormat
  winPoints: number
  drawPoints: number
  lossPoints: number
  tiebreakers: string[]
  statDefs: Array<{ key: string; label: string; aggregationType: string }>
}

export interface TournamentMatrix {
  divisionId: number
  participants: Array<{ id: number; teamName: string }>
  results: Array<
    Array<{ homeScore: number | null; awayScore: number | null; matchId: number | null }>
  >
}

export interface TournamentPromotion {
  id: number
  tournamentId: number
  fromDivisionId: number
  toDivisionId: number
  participantId: number
  teamName: string
  type: 'PROMOTION' | 'RELEGATION'
}

export interface TournamentRoster {
  id: number
  matchId: number
  teamId: number
  userId: number
  displayName: string
  jerseyNumber: number | null
  position: string | null
}

export interface TournamentHistory {
  tournamentId: number
  title: string
  seasonYear: number
  divisionName: string
  finalRank: number | null
  played: number
  won: number
  drawn: number
  lost: number
  points: number
}

export interface TournamentTeamStats {
  totalTournaments: number
  totalMatches: number
  wins: number
  draws: number
  losses: number
  goalsFor: number
  goalsAgainst: number
}
